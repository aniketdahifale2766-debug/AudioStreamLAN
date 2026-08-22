package com.audio.streamlan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log

class AudioCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "audio_capture"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "AudioStreamLAN"
        private const val SAMPLE_RATE = 48_000
    }

    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
            val resultData = intent?.parcelableIntent(EXTRA_RESULT_DATA)

            if (resultCode < 0 || resultData == null) {
                Log.e(TAG, "Missing MediaProjection permission data")
                stopSelf()
                return START_NOT_STICKY
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }

            startCapture(resultCode, resultData)
            return START_NOT_STICKY
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to start audio capture", t)
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        if (worker?.isAlive == true) return

        val manager = getSystemService(MediaProjectionManager::class.java)
        projection = manager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("MediaProjection could not be created")

        val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        recorder = createRecorder(config, AudioFormat.CHANNEL_IN_STEREO)
            ?: createRecorder(config, AudioFormat.CHANNEL_IN_MONO)
            ?: throw IllegalStateException("AudioRecord could not be initialized")

        Log.i(TAG, "AudioRecord initialized: ${recorder!!.sampleRate} Hz")
        recorder!!.startRecording()

        if (recorder!!.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("AudioRecord did not enter recording state")
        }

        worker = Thread {
            val buffer = ByteArray(SAMPLE_RATE)
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val count = recorder?.read(buffer, 0, buffer.size) ?: 0
                    if (count < 0) {
                        Log.e(TAG, "AudioRecord.read failed: $count")
                        break
                    }
                    if (count > 0) {
                        Log.d(TAG, "Captured $count PCM bytes")
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Capture loop stopped", t)
            }
        }.also { it.start() }
    }

    private fun createRecorder(
        config: AudioPlaybackCaptureConfiguration,
        channelMask: Int
    ): AudioRecord? {
        return try {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) return null

            val bufferSize = maxOf(minBuffer * 2, SAMPLE_RATE)
            AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord setup failed for channel mask $channelMask", t)
            null
        }
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun stopCapture() {
        worker?.interrupt()
        worker = null
        recorder?.runCatching { stop() }
        recorder?.release()
        recorder = null
        projection?.stop()
        projection = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AudioStreamLAN")
            .setContentText("Capturing system audio")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio capture",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}

@Suppress("DEPRECATION")
private fun Intent.parcelableIntent(key: String): Intent? {
    return if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(key, Intent::class.java)
    } else {
        getParcelableExtra(key)
    }
}
