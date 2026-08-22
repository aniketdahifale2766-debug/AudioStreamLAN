package com.audio.streamlan

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK || result.data == null) {
                status.text = "Capture permission cancelled"
                return@registerForActivityResult
            }

            val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
                putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AudioCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            status.text = "Starting audio capture…"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            text = "Ready — no capture running"
            textSize = 18f
            setPadding(32, 32, 32, 24)
        }

        val startButton = Button(this).apply {
            text = "Start audio capture"
            setOnClickListener {
                val manager = getSystemService(MediaProjectionManager::class.java)
                projectionLauncher.launch(manager.createScreenCaptureIntent())
            }
        }

        val stopButton = Button(this).apply {
            text = "Stop capture"
            setOnClickListener {
                stopService(Intent(this@MainActivity, AudioCaptureService::class.java))
                status.text = "Capture stopped"
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            addView(status)
            addView(startButton)
            addView(stopButton)
        })
    }
}
