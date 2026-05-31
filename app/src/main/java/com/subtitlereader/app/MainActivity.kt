package com.subtitlereader.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var speedSeekBar: SeekBar
    private lateinit var speedLabel: TextView

    private val REQ_OVERLAY = 1001
    private val REQ_PROJECTION = 1002
    private val REQ_NOTIF = 1003

    // SeekBar 0-30 → 0.5x-2.0x (step 0.05)
    private fun seekToSpeed(progress: Int) = 0.5f + progress * 0.05f
    private fun speedToSeek(speed: Float) = ((speed - 0.5f) / 0.05f).toInt().coerceIn(0, 30)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText   = findViewById(R.id.statusText)
        btnStart     = findViewById(R.id.btnStart)
        btnStop      = findViewById(R.id.btnStop)
        speedSeekBar = findViewById(R.id.speedSeekBar)
        speedLabel   = findViewById(R.id.speedLabel)

        btnStop.isEnabled = false

        // โหลด speed ที่บันทึกไว้
        val prefs = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE)
        val savedSpeed = prefs.getFloat(OverlayService.K_SPEED, 1.0f)
        speedSeekBar.max = 30
        speedSeekBar.progress = speedToSeek(savedSpeed)
        updateSpeedLabel(savedSpeed)

        speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val speed = seekToSpeed(progress)
                updateSpeedLabel(speed)
                // ส่ง intent ไปอัปเดต service ถ้ากำลังรัน
                val intent = Intent(this@MainActivity, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_SPEED
                    putExtra("speed", speed)
                }
                startService(intent)
                // บันทึกลง prefs
                prefs.edit().putFloat(OverlayService.K_SPEED, speed).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        btnStart.setOnClickListener { checkAndStart() }
        btnStop.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            statusText.text = "⏹ หยุดแล้ว"
            btnStart.isEnabled = true
            btnStop.isEnabled = false
        }

        updateStatus()
    }

    private fun updateSpeedLabel(speed: Float) {
        speedLabel.text = "ความเร็วเสียง: ${"%.2f".format(speed)}×"
    }

    private fun updateStatus() {
        statusText.text = when {
            !Settings.canDrawOverlays(this) -> "⚠️ ต้องการ permission 'แสดงทับแอปอื่น'\nกด Start"
            else -> "✅ พร้อมใช้งาน — กด Start"
        }
    }

    private fun checkAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF
                )
                return
            }
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQ_OVERLAY
            )
            statusText.text = "⚠️ อนุญาต 'แสดงทับแอปอื่น' แล้วกด Start อีกครั้ง"
            return
        }
        val mpMgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpMgr.createScreenCaptureIntent(), REQ_PROJECTION)
        statusText.text = "⚠️ กด 'Start now' เพื่ออนุญาต screenshot..."
    }

    override fun onRequestPermissionsResult(reqCode: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(reqCode, perms, results)
        if (reqCode == REQ_NOTIF) checkAndStart()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) checkAndStart()
                else statusText.text = "❌ ต้องการ overlay permission"
            }
            REQ_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val intent = Intent(this, OverlayService::class.java).apply {
                        putExtra("resultCode", resultCode)
                        putExtra("data", data)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        startForegroundService(intent) else startService(intent)
                    statusText.text = "✅ กำลังทำงาน\n" +
                        "• ลากกล่องไปวางบนซับ\n" +
                        "• แตะกล่อง = อ่าน 1 ครั้ง\n" +
                        "• ▶ = Auto realtime\n" +
                        "• 👁 = Ghost mode (โปร่งใส)\n" +
                        "• ↔ ดึงมุมขวาล่างเพื่อ resize"
                    btnStart.isEnabled = false
                    btnStop.isEnabled = true
                } else {
                    statusText.text = "❌ ไม่ได้รับ permission screenshot"
                }
            }
        }
    }
}
