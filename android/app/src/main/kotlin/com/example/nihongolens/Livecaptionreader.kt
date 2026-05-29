package com.example.nihongolens

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue

/**
 * LiveCaptionReader v6 — KEY FIX: use windows list, NOT rootInActiveWindow
 *
 * rootInActiveWindow returns the FOCUSED window (Termux, browser etc.)
 * We must iterate windows list and find the com.google.android.as window specifically.
 */
class LiveCaptionReader : AccessibilityService() {

    companion object {
        private const val TAG = "LiveCaptionReader"

        private val LIVE_CAPTION_PACKAGES = setOf(
            "com.google.android.as",
            "com.google.android.as.oss",
            "com.google.android.tts",
        )

        private const val TRANSLATE_URL   = "http://127.0.0.1:8765/translate_text"
        private const val CONNECT_TIMEOUT = 2_000
        private const val READ_TIMEOUT    = 12_000

        // 500ms debounce — short enough to catch rapid dialogue,
        // long enough for Live Captions to finish word-correction on each line
        private const val DEBOUNCE_MS     = 500L

        // Force-send after this long even if Live Captions keeps updating
        // Prevents infinite deferral during fast continuous speech
        private const val MAX_WAIT_MS     = 3_000L

        @Volatile var isRunning  = false
        @Volatile var instance: LiveCaptionReader? = null
    }

    private val scope         = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pendingJob:    Job? = null
    private var forceJob:      Job? = null   // fires after MAX_WAIT_MS regardless of updates
    private var translateJob:  Job? = null
    private var lastSentText   = ""
    private var lastHindiOut   = ""
    private var lastDetectedLang = ""        // track language switches
    private val translateQueue = LinkedBlockingQueue<String>(8)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance  = this
        isRunning = true

        serviceInfo = serviceInfo?.also { info ->
            info.eventTypes = (
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            )
            info.feedbackType        = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.notificationTimeout = 50
            info.flags               = (
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            )
            // Monitor confirmed Live Captions package only
            info.packageNames = LIVE_CAPTION_PACKAGES.toTypedArray()
        }

        startTranslateWorker()
        // Clear cached state from previous session
        lastSentText            = ""
        lastHindiOut            = ""
        lastTranslatedSentence  = ""
        lastDetectedLang        = ""
        lastRawCaption          = ""
        lastSentSuffix          = ""
        SpeechCaptureService.latestHindi   = ""
        SpeechCaptureService.latestEnglish = ""
        Log.i(TAG, "LiveCaptionReader v6 connected")
        scope.launch(Dispatchers.Main) {
            MainActivity.instance?.onLiveCaptionReaderConnected()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning || event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in LIVE_CAPTION_PACKAGES) return

        // readFromCaptionWindow handles all dedup internally via lastRawCaption/lastSentSuffix.
        // Do NOT add an extra lastCaptionText gate here — it causes Caption Lens to stop
        // when Live Captions is mid-sentence correcting (same send-text, different raw text).
        val sendText = readFromCaptionWindow() ?: return
        Log.d(TAG, "Caption: $sendText")
        scheduleTranslation(sendText)
    }

    private var lastTranslatedSentence = ""

    // The last raw full-text we saw from the Live Captions window
    // Used to diff against the new text and extract only the NEW suffix
    private var lastRawCaption = ""
    // Sliding window of last ~200 chars already sent — prevents resending
    // when Live Captions briefly scrolls back
    private var lastSentSuffix = ""

    private fun readFromCaptionWindow(): String? {
        val allWindows = try { windows } catch (_: Exception) { return null }
        if (allWindows.isNullOrEmpty()) return null

        for (window in allWindows) {
            val root = try { window.root } catch (_: Exception) { continue } ?: continue
            val windowPkg = root.packageName?.toString() ?: ""

            if (windowPkg in LIVE_CAPTION_PACKAGES) {
                val textNodes = mutableListOf<String>()
                collectAllText(root, textNodes)
                root.recycle()

                val validTexts = textNodes
                    .filter { isValidCaption(it) }
                    .filter { !isStaticUiLabel(it) }

                if (validTexts.isEmpty()) return null

                // Live Captions accumulates into one long node — take the longest
                val fullText = validTexts.maxByOrNull { it.length }?.trim() ?: return null

                // ── Detect Live Captions reset ──────────────────────────────
                // Live Captions clears itself after silence, starting fresh.
                // Detect this: new text is much shorter AND doesn't end with
                // anything from the old text → full reset, clear our state.
                if (lastRawCaption.isNotEmpty() &&
                    fullText.length < lastRawCaption.length / 2 &&
                    !lastRawCaption.endsWith(fullText.takeLast(20).trim())) {
                    lastRawCaption  = ""
                    lastSentSuffix  = ""
                    lastTranslatedSentence = ""
                }

                // No change at all
                if (fullText == lastRawCaption) return null

                // ── Extract only the NEW suffix ─────────────────────────────
                // Live Captions appends — so new content is always a suffix.
                // Find the longest common prefix between old and new text,
                // then everything after that is genuinely new.
                val newPart = extractNewSuffix(lastRawCaption, fullText)
                lastRawCaption = fullText

                if (newPart.isBlank() || newPart.length < 3) return null

                // ── Extract complete sentences from the new suffix ──────────
                // The new suffix may be mid-sentence (word correction still ongoing).
                // We send the last complete sentence + the incomplete tail for context.
                val toSend = buildSendText(newPart, fullText)
                if (toSend.isNullOrBlank()) return null
                if (toSend == lastSentSuffix) return null

                lastSentSuffix = toSend
                return toSend

            } else {
                root.recycle()
            }
        }
        return null
    }

    /**
     * Extract only the new content appended since last read.
     * Live Captions always appends — old text is a prefix of new text.
     * If the texts diverge (correction changed earlier words), return
     * the last sentence of the new text as a safe fallback.
     */
    private fun extractNewSuffix(old: String, new: String): String {
        if (old.isEmpty()) return new

        // Happy path: new text starts with the old text (pure append)
        if (new.startsWith(old)) {
            return new.substring(old.length).trim()
        }

        // Live Captions corrected an earlier word — find longest common prefix
        var i = 0
        val minLen = minOf(old.length, new.length)
        while (i < minLen && old[i] == new[i]) i++

        // If we share at least 60% prefix, treat rest as new suffix
        if (i > old.length * 0.6) {
            return new.substring(i).trim()
        }

        // Texts diverged significantly — Live Captions reset or corrected heavily
        // Return the last sentence fragment of the new text
        return lastSentenceOf(new)
    }

    /**
     * Build the text to actually send for translation.
     * Includes: up to 1 previous complete sentence for CT2 context
     * + the new content.
     */
    private fun buildSendText(newPart: String, fullText: String): String? {
        // Get all complete sentences from the full text so far
        val allSentences = fullText
            .split(Regex("[。！？!?]+|(?<=[.])\\s+"))
            .map { it.trim() }
            .filter { it.length >= 3 }

        if (allSentences.isEmpty()) return newPart.trim().takeIf { it.length >= 3 }

        // The last sentence may be incomplete (still being dictated)
        // Send last complete sentence + new fragment for best CT2 context
        val lastComplete = allSentences.dropLast(1).lastOrNull() ?: ""
        val lastFragment = allSentences.last()

        // If new part is mostly within the last fragment, send last 2 sentences
        val send = if (lastComplete.isNotEmpty())
            "$lastComplete $lastFragment".trim()
        else
            lastFragment

        return send.takeIf { it.length >= 3 }
    }

    private fun lastSentenceOf(text: String): String {
        val parts = text.split(Regex("[。！？!?]+|(?<=[.])\\s+"))
            .map { it.trim() }.filter { it.length >= 3 }
        return parts.lastOrNull() ?: text.takeLast(100).trim()
    }

    private fun collectAllText(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        node ?: return
        val text = node.text?.toString()?.trim() ?: ""
        if (text.isNotBlank()) out.add(text)
        for (i in 0 until node.childCount) collectAllText(node.getChild(i), out)
    }

    private fun isStaticUiLabel(text: String): Boolean {
        val lower = text.lowercase()
        // Drop Live Captions UI locale strings e.g. "English (United States)"
        // These always match the pattern: "Word (Word)" and are short
        if (text.matches(Regex("[A-Za-zÀ-ÿ ]+\\([A-Za-zÀ-ÿ ]+\\)")) && text.length < 60) return true
        if (lower.contains("united states") || lower.contains("united kingdom")) return true
        if (lower.contains("simplified") || lower.contains("traditional")) return true
        // Drop single words with no space — Live Captions UI buttons, not captions
        if (!text.contains(" ") && text.length < 15) return true
        return false
    }

    private fun isValidCaption(text: String): Boolean {
        if (text.length < 3 || text.length > 400) return false
        val letters = text.count { it.isLetter() }
        if (letters < text.length * 0.35) return false
        if (text.contains("http") || text.contains("www.")) return false
        if (text.contains("com.android") || text.contains("com.google")) return false
        // Reject locale strings like "English (United States)"
        if (text.matches(Regex(".*\\(.*\\).*")) && text.length < 50) return false
        return true
    }

    private fun scheduleTranslation(sendText: String) {
        // Detect language switch from the send text script
        val scriptNow = detectScript(sendText)
        if (scriptNow != lastDetectedLang && lastDetectedLang.isNotEmpty()) {
            lastSentText           = ""
            lastTranslatedSentence = ""
            lastHindiOut           = ""
            lastRawCaption         = ""
            lastSentSuffix         = ""
        }
        lastDetectedLang = scriptNow

        // Debounce: cancel and restart 500ms timer.
        // When timer fires, send the LATEST sendText captured at that moment —
        // stored in lastSentSuffix which readFromCaptionWindow already updated.
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(DEBOUNCE_MS)
            // Use lastSentSuffix — the most recent processed text from the window
            val toSend = lastSentSuffix.ifBlank { sendText }
            enqueueForTranslation(toSend)
        }

        // Force-send: guarantee output every MAX_WAIT_MS during continuous speech.
        // Only start a new force job if none is running.
        if (forceJob == null || forceJob?.isActive == false) {
            forceJob = scope.launch {
                delay(MAX_WAIT_MS)
                pendingJob?.cancel()
                val toSend = lastSentSuffix.ifBlank { sendText }
                enqueueForTranslation(toSend)
            }
        }
    }

    private fun enqueueForTranslation(text: String) {
        forceJob?.cancel()
        forceJob = null
        if (text.isBlank() || text == lastSentText) return
        lastSentText = text
        if (translateQueue.size >= 8) translateQueue.poll()
        translateQueue.offer(text)
    }

    /** Coarse script detection for language-switch tracking only. */
    private fun detectScript(text: String): String {
        for (c in text) {
            val cp = ord(c)
            if (cp in 0x3040..0x30FF) return "ja"
            if (cp in 0x4E00..0x9FFF) return "zh"
            if (cp in 0xAC00..0xD7AF) return "ko"
            if (cp in 0x0600..0x06FF) return "ar"
            if (cp in 0x0400..0x04FF) return "ru"
            if (cp in 0x0900..0x097F) return "hi"
        }
        return "latin"
    }

    private fun ord(c: Char) = c.code

    private fun startTranslateWorker() {
        translateJob = scope.launch {
            while (isActive) {
                val text = withContext(Dispatchers.IO) {
                    try { translateQueue.poll(2, java.util.concurrent.TimeUnit.SECONDS) }
                    catch (_: InterruptedException) { null }
                } ?: continue

                val hindi = translate(text) ?: continue
                if (hindi.isBlank()) continue
                // Don't block on lastHindiOut equality — short repeated lines
                // (e.g. "हाँ।" "ठीक है।") are valid subtitles in rapid dialogue

                Log.i(TAG, "✓ ${text.take(40)} → ${hindi.take(40)}")
                SpeechCaptureService.latestHindi   = hindi
                SpeechCaptureService.latestEnglish = text

                withContext(Dispatchers.Main) {
                    OverlayService.updateText(text, hindi)
                    MainActivity.instance?.onTranslation(text, hindi, hindi)
                }
            }
        }
    }

    private fun translate(text: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(TRANSLATE_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput       = true
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout    = READ_TIMEOUT
            val body = """{"text":${JSONObject.quote(text)},"src":"auto","tgt":"hi"}"""
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) return null
            val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            JSONObject(resp).optString("text", "").trim().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Translate error: ${e.message}")
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    override fun onInterrupt() { Log.w(TAG, "Interrupted") }

    override fun onDestroy() {
        isRunning = false; instance = null
        pendingJob?.cancel(); forceJob?.cancel()
        translateJob?.cancel(); scope.cancel()
        super.onDestroy()
    }
}
