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
import androidx.core.app.ServiceCompat

class AudioCaptureService : Service() {
    companion object {
        const val ACTION_STOP = "com.audio.streamlan.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "audio_capture"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "AudioStreamLAN"
        private const val SAMPLE_RATE = 48_000
        private const val STEREO_PACKET_BYTES = 3_840
        private const val MONO_PACKET_BYTES = 1_920
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
            saveState(false, "Error: invalid screen-capture permission result")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            // The projection consent must be granted before this foreground service is promoted.
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification("Starting local audio stream…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )

            acquireWakeLock()
            startServer()
            startCapture(resultCode, resultData)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start capture", t)
            saveState(false, "Error: ${t.javaClass.simpleName}: ${t.message ?: "capture startup failed"}")
            cleanupSafely()
        }
        return START_NOT_STICKY
    }

    private fun startServer() {
        if (server != null) return
        val newServer = LanServer(this, 8080)
        newServer.startServer()
        server = newServer
        saveState(true, newServer.url)
        updateNotification("LAN stream ready • ${newServer.url}")
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        if (worker?.isAlive == true) return

        val manager = getSystemService(MediaProjectionManager::class.java)
        val mp = manager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("MediaProjection could not be created")
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (!stopping) stopCapture()
            }
        }, null)

        val config = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        recorder = createRecorder(config, AudioFormat.CHANNEL_IN_STEREO)
            ?: createRecorder(config, AudioFormat.CHANNEL_IN_MONO)
            ?: throw IllegalStateException("AudioRecord could not be initialized on this device")

        try {
            recorder!!.startRecording()
        } catch (t: Throwable) {
            recorder?.release()
            recorder = null
            throw IllegalStateException("AudioRecord.startRecording failed: ${t.message}", t)
        }

        if (recorder!!.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("AudioRecord did not enter recording state")
        }

        val mono = recorder!!.channelCount == 1
        saveState(true, server?.url ?: "")
        updateNotification("Streaming system audio • ${server?.url}")

        worker = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val readBuffer = ByteArray(if (mono) MONO_PACKET_BYTES else STEREO_PACKET_BYTES)
            val outputBuffer = ByteArray(STEREO_PACKET_BYTES)
            var packets = 0
            try {
                while (!Thread.currentThread().isInterrupted && !stopping) {
                    val currentRecorder = recorder ?: break
                    val count = currentRecorder.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
                    if (count > 0) {
                        if (mono) {
                            var out = 0
                            var i = 0
                            while (i + 1 < count && out + 3 < outputBuffer.size) {
                                outputBuffer[out++] = readBuffer[i]
                                outputBuffer[out++] = readBuffer[i + 1]
                                outputBuffer[out++] = readBuffer[i]
                                outputBuffer[out++] = readBuffer[i + 1]
                                i += 2
                            }
                            server?.broadcastPcm(outputBuffer, out)
                        } else {
                            server?.broadcastPcm(readBuffer, count)
                        }
                        packets++
                        if (packets % 50 == 0) saveClientCount()
                    } else if (count < 0) {
                        Log.w(TAG, "AudioRecord.read returned $count")
                    }
                }
            } catch (t: Throwable) {
                if (!stopping) Log.e(TAG, "Capture loop stopped unexpectedly", t)
            }
        }.also {
            it.name = "AudioStreamLAN-Capture"
            it.start()
        }
    }

    private fun saveClientCount() {
        runCatching {
            getSharedPreferences("state", MODE_PRIVATE)
                .edit().putInt("clients", server?.clientCount ?: 0).apply()
        }
    }

    private fun createRecorder(config: AudioPlaybackCaptureConfiguration, channelMask: Int): AudioRecord? = try {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return null
        val packet = if (channelMask == AudioFormat.CHANNEL_IN_STEREO) STEREO_PACKET_BYTES else MONO_PACKET_BYTES
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
        cleanupSafely()
        stopping = false
    }

    private fun cleanupSafely() {
        runCatching { worker?.interrupt() }
        worker = null

        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null

        runCatching { projection?.stop() }
        projection = null

        runCatching { server?.stopServer() }
        server = null

        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        wakeLock = null

        runCatching {
            getSharedPreferences("state", MODE_PRIVATE)
                .edit().putBoolean("running", false).putString("value", "Stopped").putInt("clients", 0).apply()
        }

        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        runCatching { stopSelf() }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:Streaming").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun updateNotification(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text))
        }
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
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Audio streaming", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun saveState(running: Boolean, value: String) {
        runCatching {
            getSharedPreferences("state", MODE_PRIVATE).edit()
                .putBoolean("running", running)
                .putString("value", value)
                .putInt("clients", server?.clientCount ?: 0)
                .apply()
        }
    }

    override fun onDestroy() {
        // Do not call stopSelf/stopForeground recursively from onDestroy.
        if (!stopping) {
            stopping = true
            runCatching { worker?.interrupt() }
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            runCatching { projection?.stop() }
            runCatching { server?.stopServer() }
            runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
            worker = null
            recorder = null
            projection = null
            server = null
            wakeLock = null
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

@Suppress("DEPRECATION")
private fun Intent.parcelableIntent(key: String): Intent? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Intent::class.java) else getParcelableExtra(key)
