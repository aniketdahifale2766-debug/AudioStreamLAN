package com.audio.streamlan

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var urlText: TextView
    private lateinit var clientsText: TextView
    private lateinit var qr: ImageView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private var pendingProjection = false
    private val handler by lazy { android.os.Handler(mainLooper) }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val audioGranted = permissions[android.Manifest.permission.RECORD_AUDIO] == true ||
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!audioGranted) {
            pendingProjection = false
            status.text = "Microphone permission is required."
            return@registerForActivityResult
        }
        if (pendingProjection) launchProjection()
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingProjection = false
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            status.text = "Audio capture permission was cancelled."
            return@registerForActivityResult
        }
        try {
            val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
                putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AudioCaptureService.EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            status.text = "Starting stream…"
            handler.postDelayed({ refreshState() }, 500)
        } catch (t: Throwable) {
            Log.e("AudioStreamLAN", "Could not start capture service", t)
            status.text = "Service start failed: ${t.javaClass.simpleName}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildSafeUi()
    }

    private fun buildSafeUi() {
        status = TextView(this).apply { text = "Ready — audio streaming is stopped"; textSize = 19f; setPadding(8, 16, 8, 16) }
        urlText = TextView(this).apply { text = "Listener address: —"; textSize = 15f; setPadding(8, 8, 8, 8) }
        clientsText = TextView(this).apply { text = "Connected listeners: 0"; textSize = 15f; setPadding(8, 8, 8, 8) }
        qr = ImageView(this).apply { adjustViewBounds = true; setPadding(20, 20, 20, 20) }
        startButton = Button(this).apply { text = "Start Audio + LAN Server"; setOnClickListener { requestCapturePermissions() } }
        stopButton = Button(this).apply {
            text = "Stop Stream"; isEnabled = false
            setOnClickListener {
                runCatching { stopService(Intent(this@MainActivity, AudioCaptureService::class.java).apply { action = AudioCaptureService.ACTION_STOP }) }
                status.text = "Stopping…"
                handler.postDelayed({ refreshState() }, 300)
            }
        }
        val copyButton = Button(this).apply {
            text = "Copy listener address"
            setOnClickListener {
                val value = urlText.text.toString().removePrefix("Listener address: ")
                if (value.startsWith("http://")) {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("AudioStreamLAN", value))
                    status.text = "Address copied"
                }
            }
        }
        val shareButton = Button(this).apply {
            text = "Share listener address"
            setOnClickListener {
                val value = urlText.text.toString().removePrefix("Listener address: ")
                if (value.startsWith("http://")) startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, value) }, "Share AudioStreamLAN address"))
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 28)
            addView(TextView(this@MainActivity).apply { text = "AudioStreamLAN"; textSize = 28f; setPadding(8, 8, 8, 4) })
            addView(TextView(this@MainActivity).apply { text = "Stream this phone’s system audio to browsers on the same Wi‑Fi."; textSize = 14f; setPadding(8, 0, 8, 20) })
            addView(status); addView(startButton); addView(stopButton); addView(urlText); addView(clientsText); addView(copyButton); addView(shareButton)
            addView(qr, LinearLayout.LayoutParams(-1, 480))
            addView(TextView(this@MainActivity).apply { text = "After streaming starts, scan the QR code or open the listener address. Tap Start Listening in the browser and adjust the delay independently on each device."; textSize = 13f; setPadding(8, 16, 8, 8) })
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun requestCapturePermissions() {
        val permissions = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) permissions += android.Manifest.permission.POST_NOTIFICATIONS
        pendingProjection = true
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun launchProjection() {
        try {
            val manager = getSystemService(android.media.projection.MediaProjectionManager::class.java)
            if (manager == null) { pendingProjection = false; status.text = "MediaProjection is not available on this device."; return }
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        } catch (t: Throwable) {
            pendingProjection = false
            Log.e("AudioStreamLAN", "Could not request MediaProjection", t)
            status.text = "Capture request failed: ${t.javaClass.simpleName}"
        }
    }

    private fun refreshState() {
        if (isFinishing || isDestroyed) return
        val prefs = getSharedPreferences("state", MODE_PRIVATE)
        val running = prefs.getBoolean("running", false)
        val value = prefs.getString("value", "") ?: ""
        val clients = prefs.getInt("clients", 0)
        startButton.isEnabled = !running; stopButton.isEnabled = running
        if (running && value.startsWith("http://")) {
            status.text = "Streaming — open the address below"
            urlText.text = "Listener address: $value"
            clientsText.text = "Connected listeners: $clients"
            runCatching { qr.setImageBitmap(makeQr(value)) }
        } else if (!running) {
            status.text = if (value.startsWith("Error:")) value else "Ready — audio streaming is stopped"
            urlText.text = "Listener address: —"; clientsText.text = "Connected listeners: 0"; qr.setImageDrawable(null)
        }
    }

    private fun makeQr(text: String): Bitmap? = try {
        val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 480, 480)
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) pixels[y * matrix.width + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    } catch (_: Exception) { null }

    override fun onResume() { super.onResume(); refreshState() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
