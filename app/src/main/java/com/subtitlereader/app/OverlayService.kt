package com.subtitlereader.app

import android.app.*
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import android.view.*
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var prefs: SharedPreferences

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val mlkitRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var tessApi: TessBaseAPI? = null
    private lateinit var ttsHelper: TTSHelper

    private val CHANNEL_ID = "subtitle_reader_channel"
    private val NOTIF_ID = 1
    private var screenWidth = 0
    private var screenHeight = 0

    // State
    private var isAutoMode = false
    private var isGhost = false
    private var lastText = ""
    private val isCapturing = AtomicBoolean(false)
    private val tessReady = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoRunnable: Runnable? = null

    companion object {
        const val PREFS = "overlay_prefs"
        const val K_X = "x"; const val K_Y = "y"
        const val K_W = "w"; const val K_H = "h"
        const val K_AUTO = "auto"; const val K_GHOST = "ghost"
        const val K_SPEED = "speed"
        const val AUTO_MS = 1300L
        const val ACTION_STOP = "STOP"
        const val ACTION_SPEED = "SET_SPEED"
        val TESS_LANGS = listOf("tha", "eng")
        val TESS_BASE_URL = "https://cdn.jsdelivr.net/gh/tesseract-ocr/tessdata_fast@main"
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        val savedSpeed = prefs.getFloat(K_SPEED, 1.0f)
        ttsHelper = TTSHelper(this, savedSpeed)

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            screenWidth = b.width(); screenHeight = b.height()
        } else {
            val sz = Point()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(sz)
            screenWidth = sz.x; screenHeight = sz.y
        }

        // Init Tesseract ใน background
        executor.execute { initTesseract() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_SPEED -> {
                val sp = intent.getFloatExtra("speed", 1.0f)
                ttsHelper.setSpeed(sp)
                prefs.edit().putFloat(K_SPEED, sp).apply()
                return START_NOT_STICKY
            }
        }

        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent?.getParcelableExtra("data", Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra("data")

        if (resultCode == Activity.RESULT_OK && data != null) {
            val mpMgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpMgr.getMediaProjection(resultCode, data).also {
                it.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopAutoOCR()
                        virtualDisplay?.release(); imageReader?.close()
                        virtualDisplay = null; imageReader = null
                        mainHandler.post {
                            try { label()?.text = "⚠️ Screen capture หยุดแล้ว" } catch (_: Exception) {}
                        }
                    }
                }, mainHandler)
            }
            setupImageReader()
        }

        setupOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoOCR()
        if (::overlayView.isInitialized)
            try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        virtualDisplay?.release(); imageReader?.close()
        mediaProjection?.stop()
        ttsHelper.shutdown()
        mlkitRecognizer.close()
        tessApi?.recycle()
        executor.shutdown()
    }

    override fun onBind(intent: Intent?) = null

    // ── Tesseract init + download ──────────────────────────────────────────

    private fun initTesseract() {
        val dir = File(filesDir, "tessdata").also { it.mkdirs() }

        mainHandler.post { try { label()?.text = "⬇️ กำลังโหลด Thai OCR..." } catch (_: Exception) {} }

        var allReady = true
        for (lang in TESS_LANGS) {
            val f = File(dir, "$lang.traineddata")
            if (!f.exists()) {
                try {
                    mainHandler.post {
                        try { label()?.text = "⬇️ download $lang.traineddata..." } catch (_: Exception) {}
                    }
                    val url = java.net.URL("$TESS_BASE_URL/$lang.traineddata")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 15_000; conn.readTimeout = 60_000
                    conn.inputStream.use { ins -> f.outputStream().use { out -> ins.copyTo(out) } }
                    Log.i("Tess", "Downloaded $lang")
                } catch (e: Exception) {
                    Log.e("Tess", "Download $lang failed: ${e.message}")
                    f.delete()
                    allReady = false
                }
            }
        }

        if (allReady) {
            val api = TessBaseAPI()
            if (api.init(filesDir.absolutePath, "tha+eng")) {
                tessApi = api
                tessReady.set(true)
                Log.i("Tess", "Thai OCR ready")
                mainHandler.post {
                    try { label()?.text = "✅ Thai OCR พร้อม — แตะเพื่ออ่าน" } catch (_: Exception) {}
                }
            } else {
                api.recycle()
                Log.e("Tess", "init() failed, will use ML Kit")
                mainHandler.post {
                    try { label()?.text = "⚠️ Thai OCR ล้มเหลว (ใช้ ML Kit แทน)" } catch (_: Exception) {}
                }
            }
        } else {
            mainHandler.post {
                try { label()?.text = "⚠️ Download ล้มเหลว ใช้ ML Kit (ภาษาอังกฤษ)" } catch (_: Exception) {}
            }
        }
    }

    // ── MediaProjection ────────────────────────────────────────────────────

    private fun setupImageReader() {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "SubCap", screenWidth, screenHeight,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
    }

    private fun acquireScreenshot(): Bitmap? {
        val img = imageReader?.acquireLatestImage() ?: return null
        return try {
            val pl = img.planes[0]
            val pad = pl.rowStride - pl.pixelStride * screenWidth
            val bmp = Bitmap.createBitmap(
                screenWidth + pad / pl.pixelStride, screenHeight, Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(pl.buffer)
            Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight)
        } finally { img.close() }
    }

    // ── Overlay ────────────────────────────────────────────────────────────

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_view, null)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val sx = prefs.getInt(K_X, 40)
        val sy = prefs.getInt(K_Y, 350)
        val sw = prefs.getInt(K_W, 620)
        val sh = prefs.getInt(K_H, 130)
        isAutoMode = prefs.getBoolean(K_AUTO, false)
        isGhost = prefs.getBoolean(K_GHOST, false)

        layoutParams = WindowManager.LayoutParams(
            sw, sh, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = sx; y = sy }

        windowManager.addView(overlayView, layoutParams)
        applyGhost()
        syncAutoBtn()
        setupTouches()

        if (isAutoMode) startAutoOCR()
    }

    private fun setupTouches() {
        val drag   = overlayView.findViewById<View>(R.id.dragHandle)
        val resize = overlayView.findViewById<View>(R.id.resizeHandle)
        val btnOcr   = overlayView.findViewById<ImageButton>(R.id.btnOcr)
        val btnAuto  = overlayView.findViewById<ImageButton>(R.id.btnAuto)
        val btnGhost = overlayView.findViewById<ImageButton>(R.id.btnGhost)
        val btnClose = overlayView.findViewById<ImageButton>(R.id.btnClose)

        // ── Drag ──
        var ix=0; var iy=0; var tx=0f; var ty=0f; var moved=false
        drag.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    ix=layoutParams.x; iy=layoutParams.y; tx=e.rawX; ty=e.rawY; moved=false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx=(e.rawX-tx).toInt(); val dy=(e.rawY-ty).toInt()
                    if (dx*dx+dy*dy>25) moved=true
                    layoutParams.x=ix+dx; layoutParams.y=iy+dy
                    windowManager.updateViewLayout(overlayView, layoutParams); true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && !isAutoMode) triggerOCR()
                    savePos(); true
                }
                else -> false
            }
        }

        // ── Resize (corner bottom-right) ──
        var rw=0; var rh=0; var rtx=0f; var rty=0f
        resize.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { rw=layoutParams.width; rh=layoutParams.height; rtx=e.rawX; rty=e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.width  = maxOf(200, rw+(e.rawX-rtx).toInt())
                    layoutParams.height = maxOf(80,  rh+(e.rawY-rty).toInt())
                    windowManager.updateViewLayout(overlayView, layoutParams); true
                }
                MotionEvent.ACTION_UP -> { savePos(); true }
                else -> false
            }
        }

        btnOcr.setOnClickListener { triggerOCR() }

        btnAuto.setOnClickListener {
            isAutoMode = !isAutoMode
            prefs.edit().putBoolean(K_AUTO, isAutoMode).apply()
            syncAutoBtn()
            if (isAutoMode) startAutoOCR() else { stopAutoOCR(); label()?.text = "🔄 Auto off" }
        }

        btnGhost.setOnClickListener {
            isGhost = !isGhost
            prefs.edit().putBoolean(K_GHOST, isGhost).apply()
            applyGhost()
        }

        btnClose.setOnClickListener { stopSelf() }
    }

    private fun applyGhost() {
        // alpha=0 → invisible แต่ยังรับ touch ได้ (Android ไม่ block touch จาก alpha)
        overlayView.alpha = if (isGhost) 0f else 1f
    }

    private fun syncAutoBtn() {
        val btn = if (::overlayView.isInitialized)
            overlayView.findViewById<ImageButton>(R.id.btnAuto) else return
        btn.alpha = if (isAutoMode) 1f else 0.45f
        btn.setColorFilter(
            if (isAutoMode) android.graphics.Color.rgb(80,255,80)
            else android.graphics.Color.WHITE
        )
    }

    private fun label(): TextView? =
        if (::overlayView.isInitialized) overlayView.findViewById(R.id.statusLabel) else null

    private fun savePos() {
        prefs.edit()
            .putInt(K_X, layoutParams.x).putInt(K_Y, layoutParams.y)
            .putInt(K_W, layoutParams.width).putInt(K_H, layoutParams.height)
            .apply()
    }

    // ── Auto OCR loop ──────────────────────────────────────────────────────

    private fun startAutoOCR() {
        stopAutoOCR()
        autoRunnable = object : Runnable {
            override fun run() {
                if (!isAutoMode) return
                triggerOCR(autoCallback = true)
                mainHandler.postDelayed(this, AUTO_MS)
            }
        }
        mainHandler.postDelayed(autoRunnable!!, AUTO_MS)
    }

    private fun stopAutoOCR() {
        autoRunnable?.let { mainHandler.removeCallbacks(it) }
        autoRunnable = null
    }

    // ── OCR ────────────────────────────────────────────────────────────────

    private fun triggerOCR(autoCallback: Boolean = false) {
        if (imageReader == null) { label()?.text = "❌ Screen capture ไม่พร้อม"; return }
        if (!isCapturing.compareAndSet(false, true)) return  // ป้องกัน overlap

        if (!autoCallback) label()?.text = "🔍..."

        // ซ่อน overlay ก่อน screenshot
        val prevAlpha = overlayView.alpha
        overlayView.alpha = 0f

        mainHandler.postDelayed({
            val full = acquireScreenshot()
            overlayView.alpha = prevAlpha  // restore

            if (full == null) {
                isCapturing.set(false)
                if (!autoCallback) label()?.text = "❌ Screenshot ล้มเหลว"
                return@postDelayed
            }

            val cropped = crop(full)

            if (tessReady.get()) {
                executor.execute {
                    val api = tessApi
                    if (api == null) { isCapturing.set(false); return@execute }
                    api.setImage(cropped)
                    val raw = api.utF8Text?.trim() ?: ""
                    api.clear()
                    mainHandler.post { handleResult(raw, autoCallback) }
                }
            } else {
                mlkitRecognizer.process(InputImage.fromBitmap(cropped, 0))
                    .addOnSuccessListener { r -> handleResult(r.text.trim(), autoCallback) }
                    .addOnFailureListener { e ->
                        isCapturing.set(false)
                        label()?.text = "❌ OCR: ${e.message}"
                    }
            }
        }, 110)
    }

    private fun handleResult(text: String, autoMode: Boolean) {
        isCapturing.set(false)
        if (text.isEmpty()) {
            if (!autoMode) label()?.text = "❌ ไม่พบข้อความ"
            return
        }
        // Auto mode: พูดเฉพาะถ้าข้อความเปลี่ยน
        if (autoMode && text == lastText) return
        lastText = text
        label()?.text = "🔊 $text"
        ttsHelper.speak(text)
    }

    private fun crop(full: Bitmap): Bitmap {
        val x = maxOf(0, layoutParams.x)
        val y = maxOf(0, layoutParams.y)
        val w = minOf(layoutParams.width,  full.width  - x).coerceAtLeast(1)
        val h = minOf(layoutParams.height, full.height - y).coerceAtLeast(1)
        return if (w > 0 && h > 0) Bitmap.createBitmap(full, x, y, w, h) else full
    }

    // ── Notification ───────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Subtitle Reader", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Subtitle Reader กำลังทำงาน")
            .setContentText("แตะกล่องหรือกด ▶ Auto เพื่ออ่านซับ")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(android.R.drawable.ic_delete, "หยุด", pi)
            .build()
    }
}
