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
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Top(val label: String) {
    VIDEO("VIDEO"), PHOTO("PHOTO"), PORTRAIT("PORTRAIT"), PRO("PRO"),
    TIMELAPSE("TIME-LAPSE"), LONGEXP("LONG EXPOSURE"), ASTRO("ASTRO"), CLONE("CLONE")
}

enum class Kind { PHOTO, AVERAGE, LIGHTEN, CLONE, ASTRO, BOKEH, TIMELAPSE }

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

private const val CLONE_SHOTS = 5

private class Run(val kind: Kind, val w: Int, val h: Int, var skip: Int, val rot: Int, val durMs: Long) {
    val startMs = SystemClock.elapsedRealtime()
    val scratch = if (kind == Kind.TIMELAPSE) IntArray(0) else IntArray(w * h)
    var frames = 0
    @Volatile var want = false
    var avg: AverageStacker? = null
    var lt: LightenStacker? = null
    var astro: AstroStacker? = null
    val shots = ArrayList<IntArray>()
    var tl: TimelapseEncoder? = null
    var tlUri: Uri? = null
    var builder: CaptureRequest.Builder? = null
    var steps = FloatArray(0)
    var step = 0
    var settleAt = 0L
    var subjX = 0.5f
    var subjY = 0.5f
    var strength = 6
    var stretch = true
}

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
    @Volatile private var blur = 6
    @Volatile private var proAe = false
    @Volatile private var proAf = true
    @Volatile private var expP = 500
    @Volatile private var isoP = 300
    @Volatile private var focusP = 0
    @Volatile private var oisOn = false
    @Volatile private var stabMode = 0
    @Volatile private var subjX = 0.5f
    @Volatile private var subjY = 0.5f
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
        Top.PHOTO -> "Tap the shutter."
        Top.PORTRAIT -> "Tap the subject, then hold still. Focus-sweep bokeh works best on still subjects."
        Top.PRO -> "Manual shutter, ISO and focus."
        Top.TIMELAPSE -> "Use a tripod. The screen stays on while shooting."
        Top.LONGEXP -> PRESETS[lePreset].hint
        Top.ASTRO -> "Tripod, dark sky, no moon. Stars are aligned, the ground will smear."
        Top.CLONE -> "Tripod. 5 shots, move to a new spot after each one."
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
                subjX = (ev.x / v.width).coerceIn(0f, 1f)
                subjY = (ev.y / v.height).coerceIn(0f, 1f)
                overlay.ring(ev.x, ev.y)
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

    private fun selectTop(t: Top) {
        if (capturing || recording) return
        mode = t
        for (i in 0 until carouselRow.childCount) {
            val c = carouselRow.getChildAt(i) as TextView
            val sel = c.tag == t
            c.setTextColor(if (sel) ACCENT else Color.argb(150, 255, 255, 255))
            c.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
            if (sel) c.post { carouselScroll.smoothScrollTo(c.left - (carouselScroll.width - c.width) / 2, 0) }
        }
        shutterView.set(shutterLook(), 0f, true)
        configSliders()
        rebuildOptions()
        statusTv.text = hintFor(t)
        applyPreview()
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
            Top.CLONE -> setSlider(0, 9, cloneSec - 1, { "Delay between shots  " + (it + 1) + " s" }) { cloneSec = it + 1 }
            Top.PORTRAIT -> setSlider(0, 9, blur - 1, { "Blur strength  " + (it + 1) }) { blur = it + 1 }
            else -> {}
        }
        var any = false
        for (i in 0 until 3) if (sBox[i]?.visibility == View.VISIBLE) any = true
        panel.visibility = if (any) View.VISIBLE else View.GONE
    }

    private fun addChip(text: String, active: Boolean, onClick: () -> Unit) {
        optionsRow.addView(chip(text, active) {
            onClick()
            rebuildOptions()
        })
    }

    private fun resChip() {
        val s = sizes[resIdx.coerceIn(0, sizes.size - 1)]
        addChip(String.format(Locale.US, "%.1f MP", s.width * s.height / 1e6), false) {
            if (sizes.size > 1) {
                resIdx = (resIdx + 1) % sizes.size
                bg.post { createSession() }
            }
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
            Top.PORTRAIT -> addChip("TAP SUBJECT ON SCREEN", false) {}
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
        oisSupported = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
        eisSupported = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val avail = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList()
        val okS = listOf(Size(1920, 1440), Size(3264, 2448)).filter { w ->
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

    private fun setFocus(b: CaptureRequest.Builder, focus: Float?) {
        if (focus == null) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
        }
    }

    private fun applyPreview() {
        if (!::bg.isInitialized) return
        bg.post {
            val cam = camera ?: return@post
            val s = session ?: return@post
            val ps = previewSurface ?: return@post
            if (capturing || recording) return@post
            try {
                val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                b.addTarget(ps)
                if (mode == Top.PRO && !proAe) setExposure(b, seekToExp(expP), seekToIso(isoP))
                setFocus(b, if (mode == Top.PRO && !proAf) focusDiopter(focusP) else null)
                s.setRepeatingRequest(b.build(), resultCb, bg)
            } catch (e: Exception) {
                toast("Preview error: ${e.message}")
            }
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
            Top.TIMELAPSE -> Kind.TIMELAPSE
            Top.LONGEXP -> PRESETS[lePreset].kind
            Top.ASTRO -> Kind.ASTRO
            Top.CLONE -> Kind.CLONE
        }
        if ((kind == Kind.CLONE || kind == Kind.BOKEH || kind == Kind.TIMELAPSE) && rd.width > 2000) {
            setStatus("This mode needs the 2.8 MP resolution. Change it in the options row.")
            resetShutter()
            return
        }
        try {
            var manualExp = kind != Kind.PHOTO && kind != Kind.TIMELAPSE
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
                }
            }
            expNs = expNs.coerceIn(minExp, maxExp)
            iso = iso.coerceIn(minIso, maxIso)

            val steps = floatArrayOf(7f, 4.5f, 3f, 2f, 1.3f, 0.8f, 0.4f, 0f).map { min(it, minFocusDist) }.toFloatArray()
            var focus: Float? = null
            if (t == Top.PRO && !proAf) focus = focusDiopter(focusP)
            else if (t == Top.ASTRO || preset?.infinity == true) focus = 0f
            else if (manualExp) focus = lastFocus
            if (kind == Kind.BOKEH) focus = steps[0]

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
                kind == Kind.PHOTO && !manualExp -> 5
                expNs > 500_000_000L -> 1
                else -> 2
            }
            val r = Run(kind, rd.width, rd.height, skip, jpegRotation(), durMs)
            r.builder = b
            val n = rd.width * rd.height
            when (kind) {
                Kind.AVERAGE -> r.avg = AverageStacker(n)
                Kind.LIGHTEN -> r.lt = LightenStacker(n)
                Kind.ASTRO -> { r.astro = AstroStacker(rd.width, rd.height, astroAlign); r.stretch = astroStretch }
                Kind.BOKEH -> {
                    r.steps = steps
                    r.strength = blur
                    r.subjX = subjX
                    r.subjY = subjY
                }
                Kind.TIMELAPSE -> {
                    val clip = newVideo("Timelapse")
                    r.tlUri = clip.uri
                    r.tl = TimelapseEncoder(clip.pfd, rd.width, rd.height, r.rot)
                    r.want = true
                }
                else -> {}
            }
            curRun = r
            s.setRepeatingRequest(b.build(), resultCb, bg)
            capturing = true
            when (kind) {
                Kind.PHOTO -> setStatus("Capturing...")
                Kind.AVERAGE, Kind.LIGHTEN, Kind.ASTRO -> {
                    setStatus("Stacking...")
                    bg.postDelayed({ finishRun(r) }, durMs)
                    startTicker(r)
                }
                Kind.CLONE -> { setStatus("Get in position. Shot 1 in $cloneSec s"); scheduleWant(r, cloneSec * 1000L) }
                Kind.BOKEH -> { r.settleAt = SystemClock.elapsedRealtime() + 600; setStatus("Hold still, sweeping focus...") }
                Kind.TIMELAPSE -> {
                    setStatus("Time-lapse running")
                    bg.postDelayed({ finishRun(r) }, durMs)
                    startTicker(r)
                }
            }
            ui { shutterView.set(if (kind == Kind.PHOTO) 0 else 2, 0f, kind != Kind.PHOTO) }
        } catch (e: Throwable) {
            curRun = null
            capturing = false
            toast("Capture error: ${e.message}")
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
                ui { shutterView.set(2, p, true) }
                bg.postDelayed(this, 400)
            }
        }
        bg.postDelayed(tick, 400)
    }

    private fun onFrame(img: Image) {
        val r = curRun ?: return
        if (r.skip > 0) { r.skip--; return }
        when (r.kind) {
            Kind.PHOTO -> {
                Yuv.toArgb(img, r.scratch)
                finishRun(r)
            }
            Kind.AVERAGE -> {
                Yuv.toArgb(img, r.scratch)
                r.avg!!.add(r.scratch)
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
            Kind.CLONE -> {
                if (!r.want) return
                r.want = false
                Yuv.toArgb(img, r.scratch)
                r.shots.add(r.scratch.copyOf())
                ui { shutterView.set(2, r.shots.size / CLONE_SHOTS.toFloat(), true) }
                if (r.shots.size >= CLONE_SHOTS) {
                    finishRun(r)
                } else {
                    setStatus("Shot " + r.shots.size + "/" + CLONE_SHOTS + " taken. Move! Next in " + cloneSec + " s")
                    scheduleWant(r, cloneSec * 1000L)
                }
            }
            Kind.BOKEH -> {
                if (SystemClock.elapsedRealtime() < r.settleAt) return
                Yuv.toArgb(img, r.scratch)
                r.shots.add(r.scratch.copyOf())
                r.step++
                ui { shutterView.set(2, r.step / r.steps.size.toFloat(), true) }
                if (r.step >= r.steps.size) {
                    finishRun(r)
                } else {
                    val b = r.builder
                    if (b != null) {
                        b.set(CaptureRequest.LENS_FOCUS_DISTANCE, r.steps[r.step])
                        try { session?.setRepeatingRequest(b.build(), resultCb, bg) } catch (_: Exception) {}
                    }
                    r.settleAt = SystemClock.elapsedRealtime() + 450
                }
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
        setStatus("Processing...")
        ui { shutterView.set(0, 0f, false) }
        applyPreview()
        var saved: Uri? = null
        try {
            if (r.kind == Kind.TIMELAPSE) {
                saved = finishTimelapse(r)
                setStatus(if (saved != null) "Time-lapse saved to Movies/StackCam (" + r.frames + " frames)" else "No frames captured")
            } else {
                val argb: IntArray? = when (r.kind) {
                    Kind.PHOTO -> r.scratch
                    Kind.AVERAGE -> if (r.frames > 0) r.avg!!.result() else null
                    Kind.LIGHTEN -> if (r.frames > 0) r.lt!!.mx else null
                    Kind.ASTRO -> if (r.frames > 0) r.astro!!.result(r.stretch) else null
                    Kind.CLONE -> if (r.shots.size >= 2) Clone.composite(r.shots, r.w, r.h) else null
                    Kind.BOKEH -> if (r.shots.size >= 3) {
                        val sx: Float
                        val sy: Float
                        if (sensorOri == 270) { sx = 1f - r.subjY; sy = r.subjX } else { sx = r.subjY; sy = 1f - r.subjX }
                        Bokeh.compose(r.shots, r.w, r.h, sx, sy, r.strength)
                    } else null
                    Kind.TIMELAPSE -> null
                }
                if (argb == null) {
                    setStatus("Nothing to save (too few frames).")
                } else {
                    saved = savePhoto(argb, r.w, r.h, r.rot, mode.name.lowercase(Locale.US))
                    setStatus(if (saved != null) "Saved to Pictures/StackCam" else "Save failed")
                }
            }
        } catch (e: Throwable) {
            setStatus("Processing failed: " + e.javaClass.simpleName + " " + e.message)
        }
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
