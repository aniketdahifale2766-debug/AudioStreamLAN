package com.audio.streamlan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

class AudioCaptureService : Service() {

    companion object {
        const val ACTION_STOP = "com.audio.streamlan.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "audio_capture"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "AudioStreamLAN"
        private const val SAMPLE_RATE = 48_000
        private const val PACKET_BYTES = 3_840 // 20 ms, 48 kHz, stereo, 16-bit
    }

    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private var server: LanServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = intent?.parcelableIntent(EXTRA_RESULT_DATA)
        if (resultCode < 0 || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Starting local audio stream…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification("Starting local audio stream…"))
            }
            acquireWakeLock()
            startServer()
            startCapture(resultCode, resultData)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start capture", t)
            saveState(false, "Error: ${t.message ?: "capture startup failed"}")
            stopCapture()
        }
        return START_NOT_STICKY
    }

    private fun startServer() {
        if (server != null) return
        server = LanServer(this, 8080).also { it.startServer() }
        saveState(true, server!!.url)
        updateNotification("LAN stream ready • ${server!!.url}")
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        if (worker?.isAlive == true) return

        val manager = getSystemService(MediaProjectionManager::class.java)
        projection = manager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("MediaProjection could not be created")

        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (!stopping) stopCapture()
            }
        }, null)

        val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        // Stereo is preferred. Some device builds expose only mono playback capture,
        // so the fallback prevents an AudioRecord initialization crash.
        recorder = createRecorder(config, AudioFormat.CHANNEL_IN_STEREO)
            ?: createRecorder(config, AudioFormat.CHANNEL_IN_MONO)
            ?: throw IllegalStateException("AudioRecord could not be initialized")

        recorder!!.startRecording()
        if (recorder!!.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("AudioRecord did not enter recording state")
        }

        saveState(true, server?.url ?: "")
        updateNotification("Streaming system audio • ${server?.url}")

        worker = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val buffer = ByteArray(PACKET_BYTES)
            try {
                while (!Thread.currentThread().isInterrupted && !stopping) {
                    val count = recorder?.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) ?: -1
                    if (count > 0) server?.broadcastPcm(buffer, count)
                    else if (count < 0) Log.w(TAG, "AudioRecord.read returned $count")
                }
            } catch (t: Throwable) {
                if (!stopping) Log.e(TAG, "Capture loop stopped unexpectedly", t)
            }
        }.also { it.name = "AudioStreamLAN-Capture"; it.start() }
    }

    private fun createRecorder(
        config: AudioPlaybackCaptureConfiguration,
        channelMask: Int
    ): AudioRecord? = try {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return null
        val channels = if (channelMask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
        val packet = if (channels == 2) PACKET_BYTES else PACKET_BYTES / 2
        AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer * 2, packet * 4))
            .setAudioPlaybackCaptureConfig(config)
            .build()
            .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
    } catch (t: Throwable) {
        Log.w(TAG, "AudioRecord setup failed for mask $channelMask", t)
        null
    }

    private fun stopCapture() {
        if (stopping) return
        stopping = true
        worker?.interrupt()
        worker = null
        recorder?.runCatching { stop() }
        recorder?.release()
        recorder = null
        projection?.stop()
        projection = null
        server?.stopServer()
        server = null
        wakeLock?.runCatching { if (isHeld) release() }
        wakeLock = null
        saveState(false, "Stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        stopping = false
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:Streaming").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AudioStreamLAN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Audio streaming", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun saveState(running: Boolean, value: String) {
        getSharedPreferences("state", MODE_PRIVATE).edit()
            .putBoolean("running", running)
            .putString("value", value)
            .apply()
    }

    override fun onDestroy() {
        if (!stopping) stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

@Suppress("DEPRECATION")
private fun Intent.parcelableIntent(key: String): Intent? = if (Build.VERSION.SDK_INT >= 33) {
    getParcelableExtra(key, Intent::class.java)
} else {
    getParcelableExtra(key)
}
