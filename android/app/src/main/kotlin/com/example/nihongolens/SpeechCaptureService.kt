package com.example.nihongolens

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class SpeechCaptureService : Service() {

    companion object {
        const val CHANNEL_ID        = "speech_capture_channel"
        const val NOTIF_ID          = 2
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile var isRunning      = false
        @Volatile var targetLanguage = "hindi"
        @Volatile var latestOriginal = ""
        @Volatile var latestEnglish  = ""
        @Volatile var latestHindi    = ""

        private const val TAG         = "SpeechCapture"
        private const val SAMPLE_RATE = 16_000
        private const val WHISPER_URL    = "http://127.0.0.1:8765/transcribe"
        private const val WHISPER_HEALTH = "http://127.0.0.1:8765/health"

        private const val CHUNK_SECS    = 1.0
        private const val STRIDE_SECS   = 1.0   // no overlap

        private const val CHUNK_SAMPLES  = (SAMPLE_RATE * CHUNK_SECS).toInt()   // 16 000
        private const val STRIDE_SAMPLES = (SAMPLE_RATE * STRIDE_SECS).toInt()  // 16 000
        private const val CHUNK_BYTES    = CHUNK_SAMPLES  * 2                   // 32 000
        private const val STRIDE_BYTES   = STRIDE_SAMPLES * 2                   // 32 000

        private const val STALE_THRESHOLD_MS     = 1_500L
        private const val MAX_CONSECUTIVE_ERRORS = 5
        private const val WATCHDOG_TIMEOUT_MS    = 20_000L
        private const val MAX_BACKOFF_MS         = 8_000L
    }

    private val mainHandler   = Handler(Looper.getMainLooper())
    private val capturing     = AtomicBoolean(false)
    private var captureThread: Thread?            = null
    private var audioRecord:   AudioRecord?       = null
    private var mediaProjection: MediaProjection? = null
    private var wakeLock:      PowerManager.WakeLock? = null

    // Single worker — no CPU contention
    private val whisperExecutor = Executors.newSingleThreadExecutor()

    private var lastPushedHindi = ""
    private val lastPushMs      = AtomicLong(0L)

    private val consecutiveErrors  = AtomicInteger(0)
    @Volatile private var reconnecting      = false
    private var reconnectBackoffMs          = 2_000L
    private var reconnectRunnable: Runnable? = null
    private var watchdogRunnable:  Runnable? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotification("Initialising…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, buildNotification("Initialising…"))
        }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("WakelockTimeout")
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CaptionLens::SpeechCapture"
        ).also { it.acquire(60 * 60 * 1000L) }

        Log.d(TAG, "onCreate — foreground started, wakeLock acquired")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.e(TAG, "onStartCommand received null intent — stopping")
            stopSelf(); return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData: Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else
                @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Log.e(TAG, "No valid MediaProjection token")
            stopSelf(); return START_NOT_STICKY
        }

        try {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed: ${e.message}")
            stopSelf(); return START_NOT_STICKY
        }

        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is null after getMediaProjection()")
            stopSelf(); return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped externally")
                    mainHandler.post { stopSelf() }
                }
            }, Handler(Looper.getMainLooper()))
        }

        startCapture()
        scheduleWatchdog()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        isRunning    = false
        capturing.set(false)
        reconnecting = false

        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        watchdogRunnable?.let  { mainHandler.removeCallbacks(it) }

        captureThread?.interrupt()
        captureThread = null

        try { audioRecord?.stop() }    catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null

        whisperExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)

        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null

        super.onDestroy()
    }

    private fun startCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            OverlayService.updateText("", "Android 10 or newer required.")
            stopSelf(); return
        }

        val projection = mediaProjection ?: run {
            Log.e(TAG, "MediaProjection null at capture start")
            OverlayService.updateText("", "Screen capture lost — tap STOP then START again.")
            stopSelf(); return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "getMinBufferSize error: $minBuf")
            OverlayService.updateText("", "Audio init failed — tap STOP then START.")
            stopSelf(); return
        }
        val bufSize = maxOf(minBuf * 4, CHUNK_BYTES * 2)

        val captureConfig = android.media.AudioPlaybackCaptureConfiguration
            .Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val ar = try {
            AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord.Builder failed: ${e.message}")
            OverlayService.updateText("", "Audio setup failed: ${e.message}")
            stopSelf(); return
        }

        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord state=${ar.state} — not initialized")
            ar.release()
            OverlayService.updateText("", "Audio init failed — tap STOP then START.")
            stopSelf(); return
        }
        audioRecord = ar

        capturing.set(true)
        ar.startRecording()
        updateNotification("Translating video audio to Hindi…")
        OverlayService.updateText("", "Listening to video audio…")
        Log.d(TAG, "Capture started — chunk=${CHUNK_SECS}s stride=${STRIDE_SECS}s (no overlap) buf=$bufSize")

        captureThread = Thread({
            val window  = ByteArray(CHUNK_BYTES)
            var filled  = 0
            val readBuf = ByteArray(4096)

            while (capturing.get() && !Thread.currentThread().isInterrupted) {
                val rec  = audioRecord ?: break
                val read = rec.read(readBuf, 0, readBuf.size)

                if (read == AudioRecord.ERROR_INVALID_OPERATION ||
                    read == AudioRecord.ERROR_BAD_VALUE
                ) {
                    Log.e(TAG, "AudioRecord.read error: $read")
                    break
                }
                if (read <= 0) continue

                var src = 0
                while (src < read) {
                    val space  = CHUNK_BYTES - filled
                    val toCopy = minOf(read - src, space)
                    System.arraycopy(readBuf, src, window, filled, toCopy)
                    filled += toCopy
                    src    += toCopy

                    if (filled >= CHUNK_BYTES) {
                        if (!reconnecting && !whisperExecutor.isShutdown) {
                            val payload = window.copyOf(CHUNK_BYTES)
                            val stampMs = System.currentTimeMillis()
                            whisperExecutor.submit { sendToWhisper(payload, stampMs) }
                        }
                        // No overlap — fully reset window
                        filled = 0
                    }
                }
            }
            Log.d(TAG, "Capture thread ended")
        }, "AudioCaptureThread").apply {
            isDaemon = false
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    private fun sendToWhisper(pcmBytes: ByteArray, stampMs: Long) {
        // Discard stale audio before making HTTP call
        val ageMs = System.currentTimeMillis() - stampMs
        if (ageMs > STALE_THRESHOLD_MS) {
            Log.d(TAG, "Discarding stale audio chunk (${ageMs}ms old)")
            return
        }

        try {
            val wavBytes = pcmToWav(pcmBytes)

            val conn = URL(WHISPER_URL).openConnection() as HttpURLConnection
            conn.requestMethod  = "POST"
            conn.setRequestProperty("Content-Type",   "audio/wav")
            conn.setRequestProperty("Content-Length", wavBytes.size.toString())
            conn.setRequestProperty("Connection",     "keep-alive")
            conn.doOutput       = true
            conn.connectTimeout = 2_000   // fast-fail; ThreadingHTTPServer accepts instantly
            conn.readTimeout    = 12_000

            conn.outputStream.use { it.write(wavBytes) }

            val respCode = conn.responseCode
            if (respCode != 200) {
                Log.w(TAG, "Whisper HTTP $respCode")
                handleWhisperFailure("HTTP $respCode")
                return
            }

            val body      = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val json      = JSONObject(body)

            // Server-side stale-drop — not an error
            val dropped = json.optBoolean("dropped", false)
            if (dropped) {
                Log.d(TAG, "Server dropped stale job — skipping")
                return
            }

            val hindiText  = json.optString("text",        "").trim()
            val srcText    = json.optString("source_text", "").trim()
            val lang       = json.optString("language",    "")
            val confidence = json.optDouble("confidence",  0.0)

            consecutiveErrors.set(0)
            if (reconnecting) {
                reconnecting       = false
                reconnectBackoffMs = 2_000L
                Log.d(TAG, "Whisper reconnected successfully")
                mainHandler.post {
                    updateNotification("Translating video audio to Hindi…")
                    OverlayService.updateText("", "✓ Reconnected — listening…")
                    MainActivity.instance?.notifyWhisperReconnected()
                }
            }
            lastPushMs.set(System.currentTimeMillis())
            scheduleWatchdog()

            if (hindiText.length < 2 || hindiText == lastPushedHindi) return

            Log.d(TAG, "Whisper [$lang / ${(confidence * 100).toInt()}%] → HI: ${hindiText.take(60)}")

            lastPushedHindi = hindiText
            latestOriginal  = srcText
            latestEnglish   = srcText
            latestHindi     = hindiText

            mainHandler.post {
                OverlayService.updateText(srcText, hindiText)
                MainActivity.instance?.onTranslation(srcText, hindiText, hindiText)
            }

        } catch (e: Exception) {
            Log.w(TAG, "Whisper call failed: ${e.javaClass.simpleName}: ${e.message}")
            handleWhisperFailure(e.message ?: "unknown")
        }
    }

    private fun handleWhisperFailure(reason: String) {
        val errors = consecutiveErrors.incrementAndGet()
        Log.w(TAG, "Whisper error #$errors: $reason")

        if (errors >= MAX_CONSECUTIVE_ERRORS && !reconnecting) {
            reconnecting = true
            Log.w(TAG, "Entering reconnect mode after $errors consecutive errors")
            mainHandler.post {
                updateNotification("Whisper disconnected — reconnecting…")
                OverlayService.updateText("", "⚠ Reconnecting to Whisper…")
                MainActivity.instance?.notifyWhisperDisconnected()
            }
            scheduleReconnectPoll()
        }
    }

    private fun scheduleReconnectPoll() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }

        val delay = reconnectBackoffMs
        reconnectBackoffMs = minOf(reconnectBackoffMs * 2, MAX_BACKOFF_MS)

        val runnable = Runnable { pollWhisperHealth() }
        reconnectRunnable = runnable
        mainHandler.postDelayed(runnable, delay)
        Log.d(TAG, "Next whisper health poll in ${delay}ms")
    }

    private fun pollWhisperHealth() {
        if (!capturing.get()) return
        whisperExecutor.submit {
            val alive = try {
                val conn = URL(WHISPER_HEALTH).openConnection() as HttpURLConnection
                conn.requestMethod  = "GET"
                conn.connectTimeout = 1_500
                conn.readTimeout    = 1_500
                val code = conn.responseCode
                conn.disconnect()
                code == 200
            } catch (_: Exception) { false }

            if (alive) {
                consecutiveErrors.set(0)
                reconnecting       = false
                reconnectBackoffMs = 2_000L
                Log.d(TAG, "Whisper health check: server back online")
                mainHandler.post {
                    updateNotification("Translating video audio to Hindi…")
                    OverlayService.updateText("", "✓ Reconnected — listening…")
                    MainActivity.instance?.notifyWhisperReconnected()
                }
            } else {
                mainHandler.post { scheduleReconnectPoll() }
            }
        }
    }

    private fun scheduleWatchdog() {
        watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            if (!capturing.get() || reconnecting) return@Runnable
            val silenceMs = System.currentTimeMillis() - lastPushMs.get()
            if (silenceMs >= WATCHDOG_TIMEOUT_MS && lastPushMs.get() > 0) {
                Log.w(TAG, "Watchdog fired — no translation for ${silenceMs}ms")
                consecutiveErrors.set(MAX_CONSECUTIVE_ERRORS)
                handleWhisperFailure("watchdog timeout")
            } else {
                scheduleWatchdog()
            }
        }
        watchdogRunnable = runnable
        mainHandler.postDelayed(runnable, WATCHDOG_TIMEOUT_MS)
    }

    private fun pcmToWav(pcm: ByteArray): ByteArray {
        val channels    = 1
        val bitsPerSamp = 16
        val byteRate    = SAMPLE_RATE * channels * bitsPerSamp / 8
        val dataLen     = pcm.size
        val riffChunkSz = dataLen + 36

        val out = ByteArrayOutputStream(riffChunkSz + 8)
        val dos = DataOutputStream(out)

        dos.writeBytes("RIFF")
        dos.writeIntLE(riffChunkSz)
        dos.writeBytes("WAVE")
        dos.writeBytes("fmt ")
        dos.writeIntLE(16)
        dos.writeShortLE(1)
        dos.writeShortLE(channels)
        dos.writeIntLE(SAMPLE_RATE)
        dos.writeIntLE(byteRate)
        dos.writeShortLE(channels * bitsPerSamp / 8)
        dos.writeShortLE(bitsPerSamp)
        dos.writeBytes("data")
        dos.writeIntLE(dataLen)
        dos.write(pcm)
        dos.flush()
        return out.toByteArray()
    }

    private fun DataOutputStream.writeIntLE(v: Int) {
        write(v         and 0xff)
        write(v shr  8  and 0xff)
        write(v shr 16  and 0xff)
        write(v shr 24  and 0xff)
    }
    private fun DataOutputStream.writeShortLE(v: Int) {
        write(v        and 0xff)
        write(v shr 8  and 0xff)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(
                CHANNEL_ID,
                "Internal Audio Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
             .also { getSystemService(NotificationManager::class.java)
                         .createNotificationChannel(it) }
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Caption Lens — Translating to Hindi")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }
}
