package com.example.nihongolens

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.*
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat

/**
 * OverlayService — Clean 2-line subtitle display
 *
 * Behaviour:
 *  - Shows up to 2 lines at a time
 *  - Line 1 (top):  previous translation — dimmed, smaller
 *  - Line 2 (bottom): current translation — bright, bold, pill background
 *  - When a 3rd line arrives → clear both, show new line alone at bottom
 *  - 5s silence → fade out and clear everything
 *
 * This matches how professional subtitles work — page-flip, not endless scroll.
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "nihongo_overlay"
        const val NOTIF_ID   = 1

        @Volatile var latestOriginal = ""
        @Volatile var latestHindi    = ""
        @Volatile private var pushCallback: ((String, String) -> Unit)? = null

        fun updateText(original: String, hindi: String) {
            latestOriginal = original
            latestHindi    = hindi
            pushCallback?.invoke(original, hindi)
        }
    }

    // Max lines before page-flip clear
    private val MAX_LINES   = 2
    // Clear everything after this silence
    private val SILENCE_MS  = 8_000L

    // Internal display queue — translated Hindi sentences waiting to be shown.
    // Filled by updateText(), drained one sentence at a time by the display loop.
    // This is what makes FIFO work: each 2-line block stays on screen until
    // the display loop pops the next sentence from this queue.
    private val displayQueue = ArrayDeque<String>()

    private var windowManager:  WindowManager?               = null
    private var overlayView:    View?                        = null
    private var linesContainer: LinearLayout?                = null
    private var params:         WindowManager.LayoutParams?  = null
    private val mainHandler     = Handler(Looper.getMainLooper())

    @Volatile private var running   = true
    @Volatile private var viewAdded = false

    // Current lines: index 0 = older (top), index 1 = newer (bottom)
    private val lines = mutableListOf<String>()
    private var lastHindi        = ""
    private var silenceRunnable: Runnable? = null

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        mainHandler.post { if (running) buildOverlay() }
        pushCallback = { original, hindi ->
            mainHandler.post { onNewText(original, hindi) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running      = false
        pushCallback = null
        mainHandler.removeCallbacksAndMessages(null)
        lines.clear()
        displayQueue.clear()
        flipScheduled = false
        lastHindi     = ""
        if (viewAdded) {
            try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
            viewAdded = false
        }
        super.onDestroy()
    }

    // ── Text update ───────────────────────────────────────────────────────────

    private fun onNewText(original: String, hindi: String) {
        if (hindi.isBlank()) return
        if (hindi == lastHindi) return
        lastHindi = hindi

        val incoming = splitHindi(hindi.trim())
        for (sentence in incoming) {
            if (sentence.isNotBlank()) displayQueue.addLast(sentence)
        }

        // Kick the display loop whenever new content arrives
        advanceDisplay()
        rescheduleSilence()
    }

    // Called whenever new sentences arrive OR after each line is shown.
    // If lines < 2: fill from queue immediately.
    // If lines == 2: schedule a page-flip after MIN_LINE_MS, then fill again.
    private val MIN_LINE_MS = 800L   // minimum time each line stays visible
    private var flipScheduled = false

    private fun advanceDisplay() {
        // Fill empty slots immediately
        while (lines.size < MAX_LINES && displayQueue.isNotEmpty()) {
            lines.add(displayQueue.removeFirst())
            renderLines(animate = true)
        }

        // If now full and more waiting, schedule a page-flip
        if (lines.size >= MAX_LINES && displayQueue.isNotEmpty() && !flipScheduled) {
            flipScheduled = true
            mainHandler.postDelayed({
                flipScheduled = false
                if (!running) return@postDelayed
                clearWithFade {
                    lines.clear()
                    advanceDisplay()   // recurse: fill new block from queue
                }
            }, MIN_LINE_MS)
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────

    private fun renderLines(animate: Boolean) {
        val container = linesContainer ?: return
        container.removeAllViews()

        lines.forEachIndexed { index, text ->
            val isBottom = index == lines.size - 1

            val tv = TextView(this).apply {
                this.text = text
                typeface  = if (isBottom) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isBottom) 21f else 17f)
                setTextColor(if (isBottom) Color.WHITE else Color.parseColor("#BBFFFFFF"))
                setShadowLayer(8f, 0f, 2f, Color.BLACK)
                maxLines  = 2
                ellipsize = android.text.TextUtils.TruncateAt.END

                if (isBottom) {
                    background = pillBackground()
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                } else {
                    setPadding(dp(4), dp(2), dp(4), dp(2))
                }
            }

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
            container.addView(tv, lp)

            // Slide-in animation for the newest (bottom) line only
            if (isBottom && animate) {
                tv.alpha        = 0f
                tv.translationY = dp(16).toFloat()
                tv.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180)
                    .start()
            }
        }
    }

    private fun clearWithFade(then: () -> Unit) {
        val container = linesContainer ?: run { then(); return }
        val count = container.childCount
        if (count == 0) { then(); return }

        // Fade out all current views, then run callback
        var finished = 0
        for (i in 0 until count) {
            container.getChildAt(i)?.animate()
                ?.alpha(0f)
                ?.setDuration(150)
                ?.withEndAction {
                    finished++
                    if (finished == count) {
                        container.removeAllViews()
                        then()
                    }
                }
                ?.start()
        }
    }

    private fun rescheduleSilence() {
        silenceRunnable?.let { mainHandler.removeCallbacks(it) }
        silenceRunnable = Runnable {
            clearWithFade {
                lines.clear()
                displayQueue.clear()
                flipScheduled = false
                lastHindi = ""
            }
        }
        mainHandler.postDelayed(silenceRunnable!!, SILENCE_MS)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Split Hindi text at sentence boundaries (।!?) so each sentence
     * gets its own line slot rather than one giant string per update.
     */
    private fun splitHindi(text: String): List<String> {
        val parts = text.split(Regex("(?<=[।!?])|(?<=[.])\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return if (parts.isEmpty()) listOf(text) else parts
    }

    private fun pillBackground(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape        = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(Color.argb(170, 0, 0, 0))
        }

    // ── Overlay window ────────────────────────────────────────────────────────

    private fun buildOverlay() {
        try {
            val sw = resources.displayMetrics.widthPixels

            val outer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(dp(16), dp(8), dp(16), dp(8))
            }

            linesContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.TRANSPARENT)
            }

            outer.addView(linesContainer, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))

            overlayView = outer

            params = WindowManager.LayoutParams(
                sw,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                x = 0
                y = dp(80)
            }

            // Draggable
            var startRawX = 0f; var startRawY = 0f
            var initX = 0;      var initY = 0
            outer.setOnTouchListener { _, ev ->
                val p = params ?: return@setOnTouchListener false
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = ev.rawX; startRawY = ev.rawY
                        initX = p.x;         initY = p.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = initX + (ev.rawX - startRawX).toInt()
                        p.y = initY - (ev.rawY - startRawY).toInt()
                        if (viewAdded) try {
                            windowManager?.updateViewLayout(overlayView, p)
                        } catch (_: Exception) {}
                    }
                }
                true
            }

            windowManager?.addView(overlayView, params)
            viewAdded = true

        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "buildOverlay error: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "Caption Lens Overlay",
                NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
                .also { getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(it) }
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Caption Lens Active")
            .setContentText("Hindi subtitle overlay running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true).setSilent(true).build()
}
