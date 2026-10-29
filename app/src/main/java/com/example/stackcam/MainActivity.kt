package com.example.stackcam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Top(val label: String) {
    VIDEO("VIDEO"), PHOTO("PHOTO"), PORTRAIT("PORTRAIT"), NIGHT("NIGHT"), PRO("PRO"), PANO("PANO"),
    TIMELAPSE("TIME-LAPSE"), LONGEXP("LONG EXPOSURE"), ASTRO("ASTRO"), CLONE("CLONE"), DOC("DOCUMENT")
}

enum class Kind { PHOTO, AVERAGE, LIGHTEN, CLONE, ASTRO, BOKEH, TIMELAPSE, NIGHT, PANO, DOC }

data class Preset(
    val label: String,
    val kind: Kind,
    val defaultSec: Int,
    val hint: String,
    val expCapMs: Double? = null,
    val fixedExpMs: Double? = null,
    val fixedIso: Int = 800,
    val infinity: Boolean = false,
)

val PRESETS = listOf(
    Preset("Silky water", Kind.AVERAGE, 8, "Averages many frames. Tripod. Smooths water, clouds and crowds."),
    Preset("Light trails", Kind.LIGHTEN, 15, "Keeps the brightest value of every frame. Tripod, dark scene.", expCapMs = 250.0),
    Preset("Traffic trails", Kind.LIGHTEN, 20, "Shorter frames so car lights do not burn out. Tripod.", expCapMs = 125.0),
    Preset("Light painting", Kind.LIGHTEN, 30, "Draw with a light or torch in the dark. Tripod.", expCapMs = 500.0),
    Preset("Star trails", Kind.LIGHTEN, 180, "Long frames, focus at infinity. Tripod, dark sky.", fixedExpMs = 2000.0, fixedIso = 800, infinity = true),
)

private val NIGHT_LUT = IntArray(256) { i ->
    val t = Math.pow(i / 255.0, 0.78)
    (((t - 0.5) * 1.06 + 0.5).coerceIn(0.0, 1.0) * 255.0).toInt()
}

private class Run(val kind: Kind, val w: Int, val h: Int, var skip: Int, val rot: Int, val durMs: Long) {
    val startMs = SystemClock.elapsedRealtime()
    var scratch: IntArray =
        if (kind == Kind.TIMELAPSE || kind == Kind.AVERAGE || kind == Kind.NIGHT || kind == Kind.CLONE) IntArray(0) else IntArray(w * h)
    var frames = 0
    @Volatile var want = false
    @Volatile var afReady = true
    @Volatile var d0 = 0f
    var accum: YuvAccum? = null
    var lt: LightenStacker? = null
    var astro: AstroStacker? = null
    var bokeh: BokehAccum? = null
    var pano: PanoStitcher? = null
    val clones = ArrayList<YuvFrame>()
    var cloneTarget = 5
    var nightTarget = 10
    var attempts = 0
    var bestSharp = 0L
    var lumRef: ByteArray? = null
    var tl: TimelapseEncoder? = null
    var tlUri: Uri? = null
    var builder: CaptureRequest.Builder? = null
    var steps = FloatArray(0)
    var step = 0
    var settleAt = 0L
    var strength = 6
    var stretch = true

    fun release() {
        accum = null
        lt = null
        astro = null
        bokeh = null
        pano = null
        clones.clear()
        scratch = IntArray(0)
    }
}

private class Out(val px: IntArray, val w: Int, val h: Int, val rot: Int)

private class NewVideo(val uri: Uri, val pfd: ParcelFileDescriptor)

class MainActivity : Activity() {

    private lateinit var tv: TextureView
    private lateinit var vf: FrameLayout
    private lateinit var overlay: OverlayView
    private lateinit var statusTv: TextView
    private lateinit var panel: LinearLayout
    private lateinit var optionsRow: LinearLayout
    private lateinit var carouselRow: LinearLayout
    private lateinit var carouselScroll: HorizontalScrollView
    private lateinit var shutterView: ShutterView
    private lateinit var thumb: ImageView
    private val sBox = arrayOfNulls<LinearLayout>(3)
    private val sLbl = arrayOfNulls<TextView>(3)
    private val sBar = arrayOfNulls<SeekBar>(3)
    private val sFn = arrayOfNulls<(Int) -> String>(3)
    private val sCb = arrayOfNulls<(Int) -> Unit>(3)
    private var suppress = false
    private lateinit var prefs: SharedPreferences
    private lateinit var orient: OrientationEventListener

    private var bgThread: HandlerThread? = null
    private lateinit var bg: Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var chars: CameraCharacteristics? = null
    private var activeArray: Rect? = null

    private var sizes: List<Size> = listOf(Size(1920, 1440))
    private var videoSizes: List<Size> = listOf(Size(1440, 1080))
    private var resIdx = 0
    private var videoIdx = 0

    @Volatile private var minExp = 1_000_000L
    @Volatile private var maxExp = 1_000_000_000L
    @Volatile private var minIso = 50
    @Volatile private var maxIso = 3200
    @Volatile private var minFocusDist = 10f
    @Volatile private var sensorOri = 90
    @Volatile private var oisSupported = false
    @Volatile private var eisSupported = false

    @Volatile private var mode = Top.PHOTO
    @Volatile private var lePreset = 0
    @Volatile private var leSec = 8
    @Volatile private var astroSubMs = 8000
    @Volatile private var astroMin = 3
    @Volatile private var astroIsoP = 67
    @Volatile private var astroAlign = true
    @Volatile private var astroStretch = true
    @Volatile private var tlIntervalS = 2
    @Volatile private var tlMin = 10
    @Volatile private var cloneSec = 3
    @Volatile private var cloneShots = 5
    @Volatile private var nightFrames = 10
    @Volatile private var docBw = false
    @Volatile private var blur = 6
    @Volatile private var proAe = false
    @Volatile private var proAf = true
    @Volatile private var expP = 500
    @Volatile private var isoP = 300
    @Volatile private var focusP = 0
    @Volatile private var oisOn = false
    @Volatile private var stabMode = 0
    @Volatile private var afRegion: MeteringRectangle? = null
    @Volatile private var devOri = 0

    @Volatile private var lastExpNs = 33_000_000L
    @Volatile private var lastIso = 200
    @Volatile private var lastFocus = 0f

    @Volatile private var capturing = false
    @Volatile private var curRun: Run? = null

    private var mirror = false
    private var flipV = false
    private var grid = false
    private var lastUri: Uri? = null

    private var recorder: MediaRecorder? = null
    private var videoClip: NewVideo? = null
    @Volatile private var recording = false
    private var recStartMs = 0L
    private val recTick = object : Runnable {
        override fun run() {
            if (!recording) return
            val s = (SystemClock.elapsedRealtime() - recStartMs) / 1000
            statusTv.text = String.format(Locale.US, "REC  %02d:%02d", s / 60, s % 60)
            mainHandler.postDelayed(this, 500)
        }
    }

    // ------------------------------------------------------------ helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun ui(f: () -> Unit) = runOnUiThread { f() }
    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    private fun setStatus(text: String) = ui { statusTv.text = text }
    private fun ts(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private fun shutterLook() = if (mode == Top.VIDEO) 1 else 0
    private fun resetShutter() = ui { shutterView.set(shutterLook(), 0f, true) }
    private fun progress(p: Float) = ui { shutterView.set(2, p.coerceIn(0f, 1f), true) }

    private fun fmtExp(ns: Long): String =
        if (ns >= 1_000_000_000L) String.format(Locale.US, "%.1f s", ns / 1e9)
        else "1/" + (1e9 / ns).roundToInt() + " s"

    private fun seekToExp(p: Int): Long {
        val lo = ln(minExp.toDouble())
        val hi = ln(maxExp.toDouble())
        return exp(lo + (hi - lo) * p / 1000.0).toLong().coerceIn(minExp, maxExp)
    }

    private fun seekToIso(p: Int): Int {
        val lo = ln(minIso.toDouble())
        val hi = ln(maxIso.toDouble())
        return exp(lo + (hi - lo) * p / 1000.0).roundToInt().coerceIn(minIso, maxIso)
    }

    private fun astroIso(p: Int): Int {
        val lo = max(minIso, 400).toDouble()
        val hi = max(maxIso, 400).toDouble()
        return exp(ln(lo) + (ln(hi) - ln(lo)) * p / 100.0).roundToInt().coerceIn(minIso, maxIso)
    }

    private fun astroSubNs(): Long = (astroSubMs * 1_000_000L).coerceIn(minExp, maxExp)
    private fun focusDiopter(p: Int): Float = p / 100f * minFocusDist
    private fun jpegRotation(): Int = (sensorOri + devOri) % 360

    private fun hintFor(t: Top): String = when (t) {
        Top.VIDEO -> "4:3 video with sound. Pick size and stabilisation below."
        Top.PHOTO -> "Tap the screen to focus, then shoot."
        Top.PORTRAIT -> "Tap your subject to focus, then shoot and hold still. Background blur comes from a focus sweep."
        Top.NIGHT -> "Handheld is fine. Hold still until the ring completes."
        Top.PRO -> "Manual shutter, ISO and focus."
        Top.PANO -> "Press shoot, pan slowly and steadily, press again to finish. Hold the phone upright."
        Top.TIMELAPSE -> "Use a tripod. The screen stays on while shooting."
        Top.LONGEXP -> PRESETS[lePreset].hint
        Top.ASTRO -> "Tripod, dark sky, no moon. Stars are aligned, the ground will smear."
        Top.CLONE -> "Tripod. Move to a new spot after each shot. Keep the background unchanged."
        Top.DOC -> "Put the page on a darker surface and fill most of the frame."
    }

    private fun chip(text: String, active: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(if (active) Color.BLACK else Color.WHITE)
        background = roundRect(if (active) ACCENT else 0x33FFFFFF, dp(16).toFloat())
        setPadding(dp(14), dp(7), dp(14), dp(7))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(4), 0, dp(4), 0)
        layoutParams = lp
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------ UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        prefs = getSharedPreferences("stackcam", MODE_PRIVATE)
        mirror = prefs.getBoolean("mirror", false)
        flipV = prefs.getBoolean("flipV", false)
        grid = prefs.getBoolean("grid", false)
        orient = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                devOri = ((o + 45) / 90 * 90) % 360
            }
        }
        buildUi()
        selectTop(Top.PHOTO)
        applyViewFix()
    }

    private fun buildUi() {
        val matchP = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(14), 0)
        }
        val gear = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_preferences)
            setColorFilter(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { showSettings() }
        }
        topBar.addView(gear, LinearLayout.LayoutParams(dp(44), dp(44)))
        statusTv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        topBar.addView(statusTv, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(topBar, LinearLayout.LayoutParams(matchP, dp(48)))

        vf = FrameLayout(this)
        tv = TextureView(this)
        vf.addView(tv, FrameLayout.LayoutParams(matchP, matchP))
        overlay = OverlayView(this)
        vf.addView(overlay, FrameLayout.LayoutParams(matchP, matchP))
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(Color.argb(150, 0, 0, 0), dp(16).toFloat())
            setPadding(dp(14), dp(8), dp(14), dp(8))
            visibility = View.GONE
        }
        for (i in 0 until 3) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            val lbl = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 12f
            }
            val bar = SeekBar(this).apply {
                progressTintList = ColorStateList.valueOf(ACCENT)
                thumbTintList = ColorStateList.valueOf(ACCENT)
            }
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (suppress) return
                    lbl.text = sFn[i]?.invoke(p) ?: ""
                    sCb[i]?.invoke(p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { applyPreview() }
            })
            box.addView(lbl)
            box.addView(bar)
            panel.addView(box)
            sBox[i] = box
            sLbl[i] = lbl
            sBar[i] = bar
        }
        val plp = FrameLayout.LayoutParams(matchP, wrap, Gravity.BOTTOM)
        plp.setMargins(dp(12), 0, dp(12), dp(12))
        vf.addView(panel, plp)
        root.addView(vf, LinearLayout.LayoutParams(matchP, dp(300)))
        vf.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
            val w = r - l
            val h = w * 4 / 3
            if (w > 0 && v.layoutParams.height != h) {
                v.layoutParams.height = h
                v.requestLayout()
            }
        }
        tv.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                val nx = (ev.x / v.width).coerceIn(0f, 1f)
                val ny = (ev.y / v.height).coerceIn(0f, 1f)
                overlay.ring(ev.x, ev.y)
                if (mode != Top.VIDEO && !capturing && !recording) focusAt(nx, ny)
            }
            true
        }

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val optScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        optionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        optScroll.addView(optionsRow, FrameLayout.LayoutParams(wrap, matchP))
        bottom.addView(optScroll, LinearLayout.LayoutParams(matchP, dp(48)))

        carouselScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        carouselRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = resources.displayMetrics.widthPixels / 2 - dp(40)
            setPadding(pad, 0, pad, 0)
        }
        for (t in Top.values()) {
            val label = TextView(this).apply {
                text = t.label
                textSize = 14f
                setTextColor(Color.argb(150, 255, 255, 255))
                setPadding(dp(14), dp(10), dp(14), dp(10))
                tag = t
                setOnClickListener { selectTop(t) }
            }
            carouselRow.addView(label)
        }
        carouselScroll.addView(carouselRow, FrameLayout.LayoutParams(wrap, matchP))
        bottom.addView(carouselScroll, LinearLayout.LayoutParams(matchP, dp(48)))

        val row = FrameLayout(this)
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundRect(0x33FFFFFF, dp(40).toFloat())
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setOval(0, 0, v.width, v.height) }
            }
            setOnClickListener { openLast() }
        }
        val tlp = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER_VERTICAL or Gravity.START)
        tlp.leftMargin = dp(36)
        row.addView(thumb, tlp)
        shutterView = ShutterView(this)
        shutterView.setOnClickListener { onShutter() }
        row.addView(shutterView, FrameLayout.LayoutParams(dp(92), dp(92), Gravity.CENTER))
        bottom.addView(row, LinearLayout.LayoutParams(matchP, 0, 1f))
        root.addView(bottom, LinearLayout.LayoutParams(matchP, 0, 1f))

        setContentView(root)

        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { openCamera() }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun applyViewFix() {
        tv.scaleX = if (mirror) -1f else 1f
        tv.scaleY = if (flipV) -1f else 1f
        overlay.grid = grid
        overlay.invalidate()
    }

    private fun showSettings() {
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setMultiChoiceItems(
                arrayOf("Mirror preview", "Flip preview vertically", "Show grid"),
                booleanArrayOf(mirror, flipV, grid)
            ) { _, which, checked ->
                when (which) {
                    0 -> mirror = checked
                    1 -> flipV = checked
                    2 -> grid = checked
                }
                prefs.edit().putBoolean("mirror", mirror).putBoolean("flipV", flipV).putBoolean("grid", grid).apply()
                applyViewFix()
            }
            .setPositiveButton("Done", null)
            .show()
    }

    private fun openLast() {
        val u = lastUri ?: return
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(u, contentResolver.getType(u))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            toast("No gallery app found")
        }
    }

    private fun updateThumb(uri: Uri) {
        try {
            val bmp = contentResolver.loadThumbnail(uri, Size(256, 256), null)
            lastUri = uri
            ui { thumb.setImageBitmap(bmp) }
        } catch (_: Exception) {
        }
    }

    private fun allowedIdx(): List<Int> = sizes.indices.filter { i ->
        val s = sizes[i]
        when (mode) {
            Top.PANO -> s.width <= 1920
            Top.ASTRO -> s.width <= 3264
            else -> true
        }
    }

    private fun selectTop(t: Top) {
        if (capturing || recording) return
        mode = t
        afRegion = null
        for (i in 0 until carouselRow.childCount) {
            val c = carouselRow.getChildAt(i) as TextView
            val sel = c.tag == t
            c.setTextColor(if (sel) ACCENT else Color.argb(150, 255, 255, 255))
            c.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
            if (sel) c.post { carouselScroll.smoothScrollTo(c.left - (carouselScroll.width - c.width) / 2, 0) }
        }
        val al = allowedIdx()
        if (al.isNotEmpty() && resIdx !in al) {
            resIdx = al.last()
            if (::bg.isInitialized) bg.post { createSession() }
        }
        shutterView.set(shutterLook(), 0f, true)
        configSliders()
        rebuildOptions()
        statusTv.text = hintFor(t)
        applyPreview(cancel = true)
        if (t == Top.VIDEO) requestAudioIfNeeded()
    }

    private fun requestAudioIfNeeded() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
        }
    }

    private fun setSlider(i: Int, maxV: Int, progress: Int, label: (Int) -> String, cb: (Int) -> Unit) {
        val bar = sBar[i] ?: return
        suppress = true
        bar.max = maxV
        bar.progress = progress.coerceIn(0, maxV)
        suppress = false
        sFn[i] = label
        sCb[i] = cb
        sLbl[i]?.text = label(bar.progress)
        sBox[i]?.visibility = View.VISIBLE
    }

    private fun focusLabel(p: Int): String =
        if (p == 0) "Focus  infinity" else "Focus  " + (100f / focusDiopter(p)).roundToInt() + " cm"

    private fun configSliders() {
        for (i in 0 until 3) sBox[i]?.visibility = View.GONE
        when (mode) {
            Top.PRO -> {
                if (!proAe) {
                    setSlider(0, 1000, expP, { "Shutter  " + fmtExp(seekToExp(it)) }) { expP = it }
                    setSlider(1, 1000, isoP, { "ISO  " + seekToIso(it) }) { isoP = it }
                }
                if (!proAf) setSlider(2, 100, focusP, { focusLabel(it) }) { focusP = it }
            }
            Top.LONGEXP -> setSlider(0, 299, leSec - 1, { "Duration  " + (it + 1) + " s" }) { leSec = it + 1 }
            Top.ASTRO -> {
                val capMs = min(maxExp / 1_000_000.0, 15000.0)
                val p0 = (((astroSubMs - 1000) / max(capMs - 1000.0, 1.0)) * 100).roundToInt().coerceIn(0, 100)
                setSlider(0, 100, p0, {
                    String.format(Locale.US, "Sub-exposure  %.1f s", (1000 + it / 100.0 * (capMs - 1000.0)) / 1000.0)
                }) { astroSubMs = (1000 + it / 100.0 * (capMs - 1000.0)).toInt() }
                setSlider(1, 29, astroMin - 1, {
                    val frames = (it + 1) * 60000 / max(astroSubMs, 1000)
                    "Total  " + (it + 1) + " min  (about " + frames + " frames)"
                }) { astroMin = it + 1 }
                setSlider(2, 100, astroIsoP, { "ISO  " + astroIso(it) }) { astroIsoP = it }
            }
            Top.TIMELAPSE -> {
                setSlider(0, 59, tlIntervalS - 1, { "Interval  " + (it + 1) + " s" }) { tlIntervalS = it + 1 }
                setSlider(1, 119, tlMin - 1, {
                    val clip = (it + 1) * 60 / max(tlIntervalS, 1) / 30
                    "Duration  " + (it + 1) + " min  (clip about " + clip + " s)"
                }) { tlMin = it + 1 }
            }
            Top.CLONE -> {
                setSlider(0, 9, cloneSec - 1, { "Delay between shots  " + (it + 1) + " s" }) { cloneSec = it + 1 }
                setSlider(1, 5, cloneShots - 3, { "Shots  " + (it + 3) }) { cloneShots = it + 3 }
            }
            Top.PORTRAIT -> setSlider(0, 9, blur - 1, { "Blur strength  " + (it + 1) }) { blur = it + 1 }
            Top.NIGHT -> setSlider(0, 16, nightFrames - 4, { "Frames  " + (it + 4) }) { nightFrames = it + 4 }
            else -> {}
        }
        var any = false
        for (i in 0 until 3) if (sBox[i]?.visibility == View.VISIBLE) any = true
        panel.visibility = if (any) View.VISIBLE else View.GONE
    }

    private fun addChip(text: String, active: Boolean, onClick: () -> Unit) {
        optionsRow.addView(chip(text, active) {
            if (!capturing && !recording) {
                onClick()
                rebuildOptions()
            }
        })
    }

    private fun resChip() {
        val al = allowedIdx()
        if (al.size < 2) return
        val s = sizes[resIdx.coerceIn(0, sizes.size - 1)]
        addChip(String.format(Locale.US, "%.1f MP", s.width * s.height / 1e6), false) {
            val pos = al.indexOf(resIdx)
            resIdx = al[(pos + 1) % al.size]
            bg.post { createSession() }
        }
    }

    private fun oisChip() = addChip(if (oisOn) "OIS ON" else "OIS OFF", oisOn) { oisOn = !oisOn }

    private fun rebuildOptions() {
        optionsRow.removeAllViews()
        when (mode) {
            Top.PHOTO -> { resChip(); oisChip() }
            Top.PRO -> {
                addChip(if (proAe) "EXPOSURE AUTO" else "EXPOSURE MANUAL", !proAe) { proAe = !proAe; configSliders(); applyPreview() }
                addChip(if (proAf) "FOCUS AUTO" else "FOCUS MANUAL", !proAf) { proAf = !proAf; configSliders(); applyPreview() }
                resChip(); oisChip()
            }
            Top.PORTRAIT -> { addChip("TAP SUBJECT", false) {}; resChip() }
            Top.NIGHT -> { resChip(); oisChip() }
            Top.PANO -> addChip("UPRIGHT, PAN SLOWLY", false) {}
            Top.VIDEO -> {
                val vs = videoSizes[videoIdx.coerceIn(0, videoSizes.size - 1)]
                addChip(vs.height.toString() + "p 4:3", false) { if (videoSizes.size > 1) videoIdx = (videoIdx + 1) % videoSizes.size }
                addChip(arrayOf("OIS", "EIS", "STAB OFF")[stabMode], stabMode != 2) { stabMode = (stabMode + 1) % 3 }
            }
            Top.LONGEXP -> {
                for (i in PRESETS.indices) {
                    addChip(PRESETS[i].label, i == lePreset) {
                        lePreset = i
                        leSec = PRESETS[i].defaultSec
                        configSliders()
                        statusTv.text = PRESETS[i].hint
                    }
                }
                oisChip()
            }
            Top.ASTRO -> {
                addChip(if (astroAlign) "ALIGN SKY ON" else "ALIGN SKY OFF", astroAlign) { astroAlign = !astroAlign }
                addChip(if (astroStretch) "STRETCH ON" else "STRETCH OFF", astroStretch) { astroStretch = !astroStretch }
                resChip()
            }
            Top.TIMELAPSE, Top.CLONE -> resChip()
            Top.DOC -> {
                addChip(if (docBw) "BLACK & WHITE" else "COLOUR", docBw) { docBw = !docBw }
                resChip()
            }
        }
    }

    // ------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        bgThread = HandlerThread("cam").also { it.start() }
        bg = Handler(bgThread!!.looper)
        if (orient.canDetectOrientation()) orient.enable()
        if (tv.isAvailable) openCamera()
    }

    override fun onPause() {
        orient.disable()
        if (recording) stopVideo(false)
        closeCamera()
        bgThread?.quitSafely()
        bgThread = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        val ok = res.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (rc == 1) {
            if (ok) openCamera() else toast("Camera permission is required")
        } else if (rc == 2 && !ok) {
            toast("Video will be recorded without sound")
        }
    }

    private fun closeCamera() {
        val r = curRun
        capturing = false
        curRun = null
        if (r != null && r.kind == Kind.TIMELAPSE) {
            try { finishTimelapse(r) } catch (_: Exception) {}
        }
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        previewSurface?.release()
        previewSurface = null
    }

    // ------------------------------------------------------------ camera

    private fun readLimits() {
        val c = chars ?: return
        c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { minExp = it.lower; maxExp = it.upper }
        c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { minIso = it.lower; maxIso = it.upper }
        sensorOri = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        minFocusDist = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 10f
        activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        oisSupported = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
        eisSupported = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val avail = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList()
        val okS = listOf(Size(1920, 1440), Size(3264, 2448), Size(4000, 3000)).filter { w ->
            avail == null || avail.any { it.width == w.width && it.height == w.height }
        }
        sizes = if (okS.isNotEmpty()) okS else listOf(avail!!.maxByOrNull { it.width * it.height }!!)
        resIdx = 0
        val vs = map?.getOutputSizes(MediaRecorder::class.java)?.toList()
        val okV = listOf(Size(1440, 1080), Size(1920, 1440)).filter { w ->
            vs == null || vs.any { it.width == w.width && it.height == w.height }
        }
        videoSizes = if (okV.isNotEmpty()) okV else listOf(Size(1440, 1080))
        videoIdx = 0
        ui { rebuildOptions(); configSliders() }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        if (camera != null || !tv.isAvailable || bgThread == null) return
        val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
        val id = mgr.cameraIdList.firstOrNull {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        if (id == null) {
            toast("No rear camera")
            return
        }
        chars = mgr.getCameraCharacteristics(id)
        readLimits()
        mgr.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(c: CameraDevice) { camera = c; createSession() }
            override fun onDisconnected(c: CameraDevice) { c.close(); camera = null }
            override fun onError(c: CameraDevice, e: Int) { c.close(); camera = null; toast("Camera error $e") }
        }, bg)
    }

    private fun createSession() {
        val cam = camera ?: return
        try { session?.close() } catch (_: Exception) {}
        session = null
        reader?.close()
        previewSurface?.release()
        val sz = sizes[resIdx.coerceIn(0, sizes.size - 1)]
        val r = ImageReader.newInstance(sz.width, sz.height, ImageFormat.YUV_420_888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img: Image? = try { rd.acquireLatestImage() } catch (_: Exception) { null }
            if (img != null) {
                try { if (capturing) onFrame(img) } finally { img.close() }
            }
        }, bg)
        reader = r
        val st = tv.surfaceTexture ?: return
        st.setDefaultBufferSize(1440, 1080)
        val ps = Surface(st)
        previewSurface = ps
        @Suppress("DEPRECATION")
        cam.createCaptureSession(listOf(ps, r.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) { session = s; applyPreview() }
            override fun onConfigureFailed(s: CameraCaptureSession) { toast("Could not configure camera session") }
        }, bg)
    }

    private val resultCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, res: TotalCaptureResult) {
            val r = curRun
            if (r != null) {
                if (!r.afReady) {
                    val st = res.get(CaptureResult.CONTROL_AF_STATE)
                    if (st == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED || st == CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                        r.d0 = res.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: lastFocus
                        r.afReady = true
                    }
                }
                return
            }
            if (!capturing) {
                res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExpNs = it }
                res.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
                res.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastFocus = it }
            }
        }
    }

    private fun setExposure(b: CaptureRequest.Builder, expNs: Long, iso: Int) {
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(expNs, 33_333_333L))
    }

    /** focus == null: autofocus (on the tapped region if there is one); otherwise a fixed lens position in diopters. */
    private fun setFocus(b: CaptureRequest.Builder, focus: Float?) {
        if (focus == null) {
            val reg = afRegion
            if (reg != null) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(reg))
            } else {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
        }
    }

    /** Tap position is in upright viewfinder coordinates (0..1); the sensor is rotated, so it is mapped first. */
    private fun focusAt(nx: Float, ny: Float) {
        val a = activeArray ?: return
        val sx: Float
        val sy: Float
        if (sensorOri == 270) { sx = 1f - ny; sy = nx } else { sx = ny; sy = 1f - nx }
        val half = a.width() / 10
        val cx = a.left + (sx * a.width()).toInt()
        val cy = a.top + (sy * a.height()).toInt()
        val x0 = max(a.left, min(cx - half, a.right - 2 * half))
        val y0 = max(a.top, min(cy - half, a.bottom - 2 * half))
        afRegion = MeteringRectangle(x0, y0, 2 * half, 2 * half, MeteringRectangle.METERING_WEIGHT_MAX)
        applyPreview(trigger = true)
    }

    private fun applyPreview(trigger: Boolean = false, cancel: Boolean = false) {
        if (!::bg.isInitialized) return
        bg.post { previewNow(trigger, cancel) }
    }

    private fun previewNow(trigger: Boolean, cancel: Boolean) {
        val cam = camera ?: return
        val s = session ?: return
        val ps = previewSurface ?: return
        if (capturing || recording) return
        try {
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(ps)
            if (mode == Top.PRO && !proAe) setExposure(b, seekToExp(expP), seekToIso(isoP))
            setFocus(b, if (mode == Top.PRO && !proAf) focusDiopter(focusP) else null)
            s.setRepeatingRequest(b.build(), resultCb, bg)
            if (trigger) {
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                s.capture(b.build(), resultCb, bg)
            } else if (cancel) {
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                s.capture(b.build(), resultCb, bg)
            }
        } catch (e: Exception) {
            toast("Preview error: ${e.message}")
        }
    }

    // ------------------------------------------------------------ capture

    private fun onShutter() {
        if (camera == null || session == null) return
        if (mode == Top.VIDEO) {
            if (recording) bg.post { stopVideo(true) } else { shutterView.set(2, 0f, false); bg.post { startVideo() } }
            return
        }
        if (capturing) {
            bg.post { curRun?.let { finishRun(it) } }
            return
        }
        shutterView.set(shutterLook(), 0f, false)
        bg.post { beginRun() }
    }

    private fun buildSteps(d0: Float): FloatArray {
        val cand = floatArrayOf(d0, d0 * 1.8f, d0 * 1.35f, d0 * 0.75f, d0 * 0.5f, d0 * 0.3f, d0 * 0.15f, 0f)
        val out = ArrayList<Float>()
        for (c in cand) {
            val v = c.coerceIn(0f, minFocusDist)
            if (out.none { abs(it - v) < 0.06f }) out.add(v)
        }
        return out.toFloatArray()
    }

    private fun beginRun() {
        val cam = camera
        val s = session
        val rd = reader
        val ps = previewSurface
        if (cam == null || s == null || rd == null || ps == null) { resetShutter(); return }
        val t = mode
        val preset = if (t == Top.LONGEXP) PRESETS[lePreset] else null
        val kind = when (t) {
            Top.PHOTO, Top.PRO, Top.VIDEO -> Kind.PHOTO
            Top.PORTRAIT -> Kind.BOKEH
            Top.NIGHT -> Kind.NIGHT
            Top.PANO -> Kind.PANO
            Top.TIMELAPSE -> Kind.TIMELAPSE
            Top.LONGEXP -> PRESETS[lePreset].kind
            Top.ASTRO -> Kind.ASTRO
            Top.CLONE -> Kind.CLONE
            Top.DOC -> Kind.DOC
        }
        if (kind == Kind.PANO && rd.width > 1920) {
            setStatus("Panorama uses the 2.8 MP resolution.")
            resetShutter()
            return
        }
        try {
            var manualExp = kind != Kind.PHOTO && kind != Kind.TIMELAPSE && kind != Kind.DOC
            if (t == Top.PRO) manualExp = !proAe
            var expNs = lastExpNs
            var iso = lastIso
            if (t == Top.PRO && !proAe) {
                expNs = seekToExp(expP); iso = seekToIso(isoP)
            } else if (t == Top.ASTRO) {
                expNs = astroSubNs(); iso = astroIso(astroIsoP)
            } else if (preset != null) {
                val fx = preset.fixedExpMs
                val cap = preset.expCapMs
                if (fx != null) {
                    expNs = (fx * 1e6).toLong(); iso = preset.fixedIso
                } else if (cap != null) {
                    val capNs = (cap * 1e6).toLong()
                    if (expNs > capNs) { iso = (iso.toDouble() * expNs / capNs).toInt(); expNs = capNs }
                } else if (kind == Kind.AVERAGE) {
                    // fill the gap between frames: stretch exposure toward 1/30 s, lowering ISO to compensate
                    val frameNs = 33_333_333L
                    if (expNs < frameNs) {
                        val newIso = (iso.toDouble() * expNs / frameNs).toInt()
                        if (newIso >= minIso) {
                            expNs = frameNs; iso = newIso
                        } else {
                            expNs = (expNs * (iso.toDouble() / minIso)).toLong(); iso = minIso
                        }
                    }
                }
            } else if (kind == Kind.NIGHT) {
                val capNs = 150_000_000L
                if (expNs > capNs) { iso = (iso.toDouble() * expNs / capNs).toInt(); expNs = capNs }
            }
            expNs = expNs.coerceIn(minExp, maxExp)
            iso = iso.coerceIn(minIso, maxIso)

            var focus: Float? = null
            if (t == Top.PRO && !proAf) focus = focusDiopter(focusP)
            else if (t == Top.ASTRO || preset?.infinity == true) focus = 0f
            val useAf = focus == null && kind != Kind.TIMELAPSE

            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(ps)
            b.addTarget(rd.surface)
            if (manualExp) {
                setExposure(b, expNs, iso)
                if (t == Top.ASTRO || preset?.infinity == true) {
                    b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT)
                } else {
                    b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                }
            }
            setFocus(b, focus)
            if (oisOn && oisSupported && t != Top.ASTRO) {
                b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
            }

            val durMs = when (t) {
                Top.LONGEXP -> leSec * 1000L
                Top.ASTRO -> astroMin * 60_000L
                Top.TIMELAPSE -> tlMin * 60_000L
                else -> 0L
            }
            val skip = when {
                (kind == Kind.PHOTO || kind == Kind.DOC) && !manualExp -> 3
                expNs > 500_000_000L -> 1
                else -> 2
            }
            val r = Run(kind, rd.width, rd.height, skip, jpegRotation(), durMs)
            r.builder = b
            r.afReady = !useAf
            r.cloneTarget = cloneShots
            r.nightTarget = nightFrames
            r.strength = blur
            when (kind) {
                Kind.AVERAGE, Kind.NIGHT -> r.accum = YuvAccum(rd.width, rd.height)
                Kind.LIGHTEN -> r.lt = LightenStacker(rd.width * rd.height)
                Kind.ASTRO -> { r.astro = AstroStacker(rd.width, rd.height, astroAlign); r.stretch = astroStretch }
                Kind.BOKEH -> r.bokeh = BokehAccum(rd.width, rd.height)
                Kind.TIMELAPSE -> {
                    val clip = newVideo("Timelapse")
                    r.tlUri = clip.uri
                    r.tl = TimelapseEncoder(clip.pfd, rd.width, rd.height, r.rot)
                    r.want = true
                }
                Kind.CLONE, Kind.PANO -> r.want = true
                else -> {}
            }
            curRun = r
            s.setRepeatingRequest(b.build(), resultCb, bg)
            if (useAf) {
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                s.capture(b.build(), resultCb, bg)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            }
            capturing = true
            when (kind) {
                Kind.PHOTO, Kind.DOC -> setStatus("Focusing...")
                Kind.AVERAGE, Kind.LIGHTEN, Kind.ASTRO -> {
                    setStatus("Stacking...")
                    bg.postDelayed({ finishRun(r) }, durMs)
                    startTicker(r)
                }
                Kind.NIGHT -> {
                    setStatus("Hold still...")
                    bg.postDelayed({ finishRun(r) }, 90_000L)
                }
                Kind.CLONE -> {
                    setStatus("Focusing, then get in position. Shot 1 in $cloneSec s")
                    scheduleWant(r, cloneSec * 1000L + 1500L)
                }
                Kind.BOKEH -> setStatus("Focusing on your subject...")
                Kind.PANO -> setStatus("Pan slowly...")
                Kind.TIMELAPSE -> {
                    setStatus("Time-lapse running")
                    bg.postDelayed({ finishRun(r) }, durMs)
                    startTicker(r)
                }
            }
            ui { shutterView.set(if (kind == Kind.PHOTO || kind == Kind.DOC) 0 else 2, 0f, kind != Kind.PHOTO && kind != Kind.DOC) }
        } catch (e: Throwable) {
            curRun?.release()
            curRun = null
            capturing = false
            toast(if (e is OutOfMemoryError) "Not enough memory at this resolution. Pick a lower one." else "Capture error: " + e.message)
            resetShutter()
        }
    }

    private fun scheduleWant(r: Run, ms: Long) {
        bg.postDelayed({ if (curRun === r) r.want = true }, ms)
    }

    private fun startTicker(r: Run) {
        val tick = object : Runnable {
            override fun run() {
                if (curRun !== r) return
                val p = ((SystemClock.elapsedRealtime() - r.startMs).toFloat() / r.durMs).coerceIn(0f, 1f)
                progress(p)
                bg.postDelayed(this, 400)
            }
        }
        bg.postDelayed(tick, 400)
    }

    private fun applyBokehFocus(r: Run) {
        val b = r.builder ?: return
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        b.set(CaptureRequest.LENS_FOCUS_DISTANCE, r.steps[r.step])
        try { session?.setRepeatingRequest(b.build(), resultCb, bg) } catch (_: Exception) {}
    }

    private fun onFrame(img: Image) {
        val r = curRun ?: return
        if (!r.afReady) {
            if (SystemClock.elapsedRealtime() - r.startMs > 2500) {
                r.afReady = true
                r.d0 = lastFocus
            } else {
                return
            }
        }
        if (r.skip > 0) { r.skip--; return }
        try {
            handleFrame(r, img)
        } catch (e: Throwable) {
            curRun = null
            capturing = false
            r.release()
            setStatus(if (e is OutOfMemoryError) "Out of memory. Pick a lower resolution." else "Frame error: " + e.javaClass.simpleName + " " + e.message)
            applyPreview(cancel = true)
            resetShutter()
        }
    }

    private fun handleFrame(r: Run, img: Image) {
        when (r.kind) {
            Kind.PHOTO, Kind.DOC -> {
                Yuv.toArgb(img, r.scratch)
                finishRun(r)
            }
            Kind.AVERAGE -> {
                val acc = r.accum!!
                acc.load(img)
                acc.addLoaded(0, 0)
                r.frames++
                if (r.frames % 3 == 0) setStatus("Stacking... " + r.frames + " frames")
            }
            Kind.LIGHTEN -> {
                Yuv.toArgb(img, r.scratch)
                r.lt!!.add(r.scratch)
                r.frames++
                if (r.frames % 3 == 0) setStatus("Stacking... " + r.frames + " frames")
            }
            Kind.ASTRO -> {
                Yuv.toArgb(img, r.scratch)
                r.astro!!.add(r.scratch)
                r.frames++
                setStatus("Astro: " + r.frames + " frames stacked")
            }
            Kind.NIGHT -> {
                val acc = r.accum!!
                acc.load(img)
                val lw = r.w / 4
                val lh = r.h / 4
                val low = acc.lowLum(4)
                val sh = Align.sharp(low, lw, lh)
                r.attempts++
                val ref = r.lumRef
                if (ref == null) {
                    r.lumRef = low
                    r.bestSharp = sh
                    acc.addLoaded(0, 0)
                    r.frames++
                } else {
                    if (sh < r.bestSharp * 7 / 10 && r.attempts < r.nightTarget * 2) return
                    if (sh > r.bestSharp) r.bestSharp = sh
                    val d = Align.shift(ref, low, lw, lh, 10, 10, 0, 0, 2)
                    acc.addLoaded(d[0] * 4, d[1] * 4)
                    r.frames++
                }
                progress(r.frames / r.nightTarget.toFloat())
                setStatus("Night: " + r.frames + "/" + r.nightTarget + " frames")
                if (r.frames >= r.nightTarget || r.attempts >= r.nightTarget * 2) finishRun(r)
            }
            Kind.CLONE -> {
                if (!r.want) return
                r.want = false
                r.clones.add(YuvFrame.from(img))
                progress(r.clones.size / r.cloneTarget.toFloat())
                if (r.clones.size >= r.cloneTarget) {
                    finishRun(r)
                } else {
                    setStatus("Shot " + r.clones.size + "/" + r.cloneTarget + " taken. Move! Next in " + cloneSec + " s")
                    scheduleWant(r, cloneSec * 1000L)
                }
            }
            Kind.BOKEH -> {
                val bk = r.bokeh!!
                val now = SystemClock.elapsedRealtime()
                if (r.steps.isEmpty()) {
                    r.steps = buildSteps(r.d0.coerceIn(0f, minFocusDist))
                    r.step = 0
                    if (r.steps.size < 3) {
                        setStatus("Subject too far for the blur effect. Move closer.")
                        finishRun(r)
                        return
                    }
                    setStatus("Hold still, sweeping focus...")
                    applyBokehFocus(r)
                    r.settleAt = now + 600
                    return
                }
                if (now < r.settleAt) return
                bk.add(img, r.steps[r.step], r.step == 0, r.scratch)
                r.step++
                progress(r.step / r.steps.size.toFloat())
                if (r.step >= r.steps.size) {
                    finishRun(r)
                } else {
                    applyBokehFocus(r)
                    r.settleAt = SystemClock.elapsedRealtime() + 450
                }
            }
            Kind.PANO -> {
                if (!r.want) return
                r.want = false
                Yuv.toArgb(img, r.scratch)
                val rt = Rot.cw(r.scratch, r.w, r.h, r.rot)
                var p = r.pano
                if (p == null) {
                    p = PanoStitcher(rt.w, rt.h)
                    r.pano = p
                }
                val st = p.add(rt.px)
                r.frames = p.frames
                progress(p.painted / p.limit.toFloat())
                setStatus(
                    when (st) {
                        2 -> "Too fast, pan slower (" + p.frames + " frames)"
                        3 -> "Panorama full"
                        else -> "Panorama: " + p.frames + " frames, keep panning"
                    }
                )
                if (st == 3) { finishRun(r); return }
                scheduleWant(r, 300)
            }
            Kind.TIMELAPSE -> {
                if (!r.want) return
                r.want = false
                r.tl?.add(img)
                r.frames++
                setStatus("Time-lapse: " + r.frames + " frames (clip about " + r.frames / 30 + " s)")
                scheduleWant(r, tlIntervalS * 1000L)
            }
        }
    }

    private fun finishTimelapse(r: Run): Uri? {
        val tl = r.tl ?: return null
        val uri = r.tlUri ?: return null
        tl.finish()
        if (tl.frames == 0) {
            contentResolver.delete(uri, null, null)
            return null
        }
        publish(uri)
        return uri
    }

    private fun finishRun(r: Run) {
        if (curRun !== r) return
        capturing = false
        curRun = null
        setStatus("Processing... (can take a few seconds)")
        ui { shutterView.set(0, 0f, false) }
        applyPreview(cancel = true)
        var saved: Uri? = null
        try {
            if (r.kind == Kind.TIMELAPSE) {
                saved = finishTimelapse(r)
                setStatus(if (saved != null) "Time-lapse saved to Movies/StackCam (" + r.frames + " frames)" else "No frames captured")
            } else {
                val out: Out? = when (r.kind) {
                    Kind.PHOTO -> Out(r.scratch, r.w, r.h, r.rot)
                    Kind.AVERAGE -> r.accum?.let { if (it.frames > 0) Out(it.result(null), r.w, r.h, r.rot) else null }
                    Kind.NIGHT -> r.accum?.let { if (it.frames > 0) Out(it.result(NIGHT_LUT), r.w, r.h, r.rot) else null }
                    Kind.LIGHTEN -> r.lt?.let { if (r.frames > 0) Out(it.mx, r.w, r.h, r.rot) else null }
                    Kind.ASTRO -> r.astro?.let { if (r.frames > 0) Out(it.result(r.stretch), r.w, r.h, r.rot) else null }
                    Kind.CLONE -> if (r.clones.size >= 2) Out(CloneV2.composite(r.clones, r.w, r.h), r.w, r.h, r.rot) else null
                    Kind.BOKEH -> r.bokeh?.let { if (it.shots >= 3) Out(it.result(r.strength), r.w, r.h, r.rot) else null }
                    Kind.PANO -> r.pano?.finish()?.let { Out(it.px, it.w, it.h, 0) }
                    Kind.DOC -> {
                        val rt = Rot.cw(r.scratch, r.w, r.h, r.rot)
                        val d = DocScan.process(rt.px, rt.w, rt.h, docBw)
                        Out(d.px, d.w, d.h, 0)
                    }
                    Kind.TIMELAPSE -> null
                }
                r.release()
                if (out == null) {
                    setStatus("Nothing to save (too few frames).")
                } else {
                    saved = savePhoto(out.px, out.w, out.h, out.rot, mode.name.lowercase(Locale.US))
                    setStatus(if (saved != null) "Saved to Pictures/StackCam" else "Save failed")
                }
            }
        } catch (e: Throwable) {
            setStatus(
                if (e is OutOfMemoryError) "Out of memory. Pick a lower resolution."
                else "Processing failed: " + e.javaClass.simpleName + " " + e.message
            )
        }
        r.release()
        resetShutter()
        saved?.let { updateThumb(it) }
    }

    // ------------------------------------------------------------ saving

    private fun savePhoto(argb: IntArray, w: Int, h: Int, rot: Int, tag: String): Uri? {
        val rt = Rot.cw(argb, w, h, rot)
        val bmp = Bitmap.createBitmap(rt.px, rt.w, rt.h, Bitmap.Config.ARGB_8888)
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "StackCam_" + tag + "_" + ts() + ".jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StackCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        if (uri == null) {
            bmp.recycle()
            return null
        }
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        publish(uri)
        bmp.recycle()
        return uri
    }

    private fun newVideo(prefix: String): NewVideo {
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "StackCam_" + prefix + "_" + ts() + ".mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/StackCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
            ?: throw IllegalStateException("Could not create video file")
        val pfd = contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException("Could not open video file")
        return NewVideo(uri, pfd)
    }

    private fun publish(uri: Uri) {
        val cv = ContentValues()
        cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
        contentResolver.update(uri, cv, null, null)
    }

    // ------------------------------------------------------------ video

    private fun abortVideo(msg: String) {
        recording = false
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        val clip = videoClip
        videoClip = null
        if (clip != null) {
            try { clip.pfd.close() } catch (_: Exception) {}
            try { contentResolver.delete(clip.uri, null, null) } catch (_: Exception) {}
        }
        toast(msg)
        createSession()
        resetShutter()
    }

    private fun startVideo() {
        val cam = camera
        val ps = previewSurface
        if (cam == null || ps == null) { resetShutter(); return }
        try {
            val size = videoSizes[videoIdx.coerceIn(0, videoSizes.size - 1)]
            val clip = newVideo("Video")
            videoClip = clip
            @Suppress("DEPRECATION")
            val rec = MediaRecorder()
            val withAudio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (withAudio) rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setOutputFile(clip.pfd.fileDescriptor)
            rec.setVideoEncodingBitRate(if (size.width >= 1920) 40_000_000 else 24_000_000)
            rec.setVideoFrameRate(30)
            rec.setVideoSize(size.width, size.height)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (withAudio) {
                rec.setAudioEncodingBitRate(128_000)
                rec.setAudioSamplingRate(44_100)
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            }
            rec.setOrientationHint(jpegRotation())
            rec.prepare()
            recorder = rec
            try { session?.close() } catch (_: Exception) {}
            session = null
            val rs = rec.surface
            @Suppress("DEPRECATION")
            cam.createCaptureSession(listOf(ps, rs), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    try {
                        session = s
                        val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        b.addTarget(ps)
                        b.addTarget(rs)
                        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        if (stabMode == 0 && oisSupported) {
                            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
                        } else if (stabMode == 1 && eisSupported) {
                            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                        }
                        s.setRepeatingRequest(b.build(), null, bg)
                        rec.start()
                        recording = true
                        recStartMs = SystemClock.elapsedRealtime()
                        ui {
                            shutterView.set(2, 0f, true)
                            mainHandler.post(recTick)
                        }
                    } catch (e: Exception) {
                        abortVideo("Video error: " + e.message)
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { abortVideo("Could not start video session") }
            }, bg)
        } catch (e: Exception) {
            abortVideo("Video error: " + e.message)
        }
    }

    private fun stopVideo(restore: Boolean) {
        if (!recording) return
        recording = false
        val rec = recorder
        val clip = videoClip
        try { session?.stopRepeating() } catch (_: Exception) {}
        var ok = true
        try { rec?.stop() } catch (_: RuntimeException) { ok = false }
        try { rec?.release() } catch (_: Exception) {}
        recorder = null
        videoClip = null
        if (clip != null) {
            try { clip.pfd.close() } catch (_: Exception) {}
            if (ok) {
                publish(clip.uri)
                setStatus("Video saved to Movies/StackCam")
                if (restore) updateThumb(clip.uri)
            } else {
                contentResolver.delete(clip.uri, null, null)
                setStatus("Clip too short, discarded")
            }
        }
        if (restore) {
            createSession()
            resetShutter()
        }
    }
}
