package com.example.stackcam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
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
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

enum class Kind { PHOTO, AVERAGE, LIGHTEN, CLONE }

data class Mode(
    val label: String,
    val kind: Kind,
    val defaultSec: Int,
    val hint: String,
    val expCapMs: Double? = null,
    val fixedExpMs: Double? = null,
    val fixedIso: Int = 800,
    val infinity: Boolean = false,
)

val MODES = listOf(
    Mode("Photo / Pro", Kind.PHOTO, 0, "One frame. Switch on Manual exposure for Pro controls."),
    Mode("Silky water", Kind.AVERAGE, 8, "Averages many frames. Use a tripod. Smooths water, clouds and crowds."),
    Mode("Light trails", Kind.LIGHTEN, 15, "Keeps the brightest value of every frame. Tripod, dark scene.", expCapMs = 250.0),
    Mode("Traffic trails", Kind.LIGHTEN, 20, "Same as light trails with shorter frames so car lights do not burn out.", expCapMs = 125.0),
    Mode("Light painting", Kind.LIGHTEN, 30, "Draw with a light or phone torch in the dark. Tripod.", expCapMs = 500.0),
    Mode("Star trails", Kind.LIGHTEN, 180, "Long frames, focus at infinity. Tripod, very dark sky.", fixedExpMs = 2000.0, fixedIso = 800, infinity = true),
    Mode("Clone", Kind.CLONE, 3, "5 shots from a fixed tripod. Move to a new spot after each shot. The slider sets the seconds between shots.")
)

private const val CLONE_SHOTS = 5

private class Run(val kind: Kind, val w: Int, val h: Int, var skip: Int) {
    val scratch = IntArray(w * h)
    var frames = 0
    @Volatile var want = false
    val avg: AverageStacker? = if (kind == Kind.AVERAGE) AverageStacker(w * h) else null
    val lt: LightenStacker? = if (kind == Kind.LIGHTEN) LightenStacker(w * h) else null
    val cloneFrames = ArrayList<IntArray>()
}

class MainActivity : Activity() {

    private lateinit var tv: TextureView
    private lateinit var info: TextView
    private lateinit var hint: TextView
    private lateinit var status: TextView
    private lateinit var manualSw: Switch
    private lateinit var oisSw: Switch
    private lateinit var expRow: LinearLayout
    private lateinit var expLabel: TextView
    private lateinit var expBar: SeekBar
    private lateinit var isoLabel: TextView
    private lateinit var isoBar: SeekBar
    private lateinit var durRow: LinearLayout
    private lateinit var durLabel: TextView
    private lateinit var durBar: SeekBar
    private lateinit var resBtn: Button
    private lateinit var shutter: Button

    private var bgThread: HandlerThread? = null
    private lateinit var bg: Handler
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var chars: CameraCharacteristics? = null

    private var sizes: List<Size> = listOf(Size(1920, 1440))
    private var resIdx = 0

    @Volatile private var minExp = 1_000_000L
    @Volatile private var maxExp = 1_000_000_000L
    @Volatile private var minIso = 50
    @Volatile private var maxIso = 3200
    @Volatile private var oisSupported = false

    @Volatile private var modeIdx = 0
    @Volatile private var manualOn = false
    @Volatile private var oisOn = false
    @Volatile private var expP = 500
    @Volatile private var isoP = 300
    @Volatile private var durSec = 8

    @Volatile private var lastExpNs = 33_000_000L
    @Volatile private var lastIso = 200
    @Volatile private var lastFocus = 0f

    @Volatile private var capturing = false
    @Volatile private var curRun: Run? = null

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun ui(f: () -> Unit) = runOnUiThread { f() }
    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    private fun SeekBar.onChange(f: (Int) -> Unit) {
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { f(p) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 14f
        setPadding(0, dp(6), 0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
        }
        tv = TextureView(this)
        tv.addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            val w = h * 4 / 3
            if (h > 0 && v.layoutParams.width != w) {
                v.layoutParams.width = w
                v.requestLayout()
            }
        }
        root.addView(tv, LinearLayout.LayoutParams(dp(400), ViewGroup.LayoutParams.MATCH_PARENT))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        val scroll = ScrollView(this)
        scroll.addView(panel)
        root.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, MODES.map { it.label })
        panel.addView(spinner)

        hint = label("")
        hint.textSize = 12f
        panel.addView(hint)

        info = label("")
        info.textSize = 12f
        panel.addView(info)

        manualSw = Switch(this).apply { text = "Manual exposure"; setTextColor(Color.WHITE) }
        panel.addView(manualSw)

        expRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        expLabel = label("")
        expBar = SeekBar(this).apply { max = 1000; progress = expP }
        isoLabel = label("")
        isoBar = SeekBar(this).apply { max = 1000; progress = isoP }
        expRow.addView(expLabel); expRow.addView(expBar); expRow.addView(isoLabel); expRow.addView(isoBar)
        panel.addView(expRow)

        oisSw = Switch(this).apply { text = "OIS / gimbal on (off is better on a tripod)"; setTextColor(Color.WHITE) }
        panel.addView(oisSw)

        durRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        durLabel = label("")
        durBar = SeekBar(this).apply { max = 299; progress = durSec - 1 }
        durRow.addView(durLabel); durRow.addView(durBar)
        panel.addView(durRow)

        resBtn = Button(this)
        panel.addView(resBtn)

        shutter = Button(this).apply { text = "Shoot"; textSize = 20f }
        panel.addView(shutter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)))

        status = label("Ready")
        panel.addView(status)

        setContentView(root)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                modeIdx = pos
                val m = MODES[pos]
                hint.text = m.hint
                durRow.visibility = if (m.kind == Kind.PHOTO) View.GONE else View.VISIBLE
                if (m.defaultSec > 0) durBar.progress = m.defaultSec - 1
                updateLabels()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        manualSw.setOnCheckedChangeListener { _, on ->
            manualOn = on
            expRow.visibility = if (on) View.VISIBLE else View.GONE
            applyPreview()
        }
        oisSw.setOnCheckedChangeListener { _, on -> oisOn = on }
        expBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { expP = p; updateLabels() }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { applyPreview() }
        })
        isoBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { isoP = p; updateLabels() }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { applyPreview() }
        })
        durBar.onChange { durSec = it + 1; updateLabels() }
        resBtn.setOnClickListener {
            if (capturing) return@setOnClickListener
            resIdx = (resIdx + 1) % sizes.size
            updateLabels()
            bg.post { createSession() }
        }
        shutter.setOnClickListener { onShutter() }

        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { openCamera() }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        updateLabels()
    }

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

    private fun updateLabels() {
        ui {
            expLabel.text = "Shutter: " + fmtExp(seekToExp(expP))
            isoLabel.text = "ISO: " + seekToIso(isoP)
            val m = MODES[modeIdx]
            durLabel.text = if (m.kind == Kind.CLONE) "Seconds between shots: $durSec ($CLONE_SHOTS shots)" else "Duration: $durSec s"
            val s = sizes[resIdx.coerceIn(0, sizes.size - 1)]
            resBtn.text = "Resolution: ${s.width}x${s.height} (tap to change)"
            info.text = "Sensor limits: shutter ${fmtExp(minExp)} to ${fmtExp(maxExp)}, ISO $minIso to $maxIso"
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        bgThread = HandlerThread("cam").also { it.start() }
        bg = Handler(bgThread!!.looper)
        @Suppress("DEPRECATION")
        tv.rotation = if (windowManager.defaultDisplay.rotation == Surface.ROTATION_270) 180f else 0f
        if (tv.isAvailable) openCamera()
    }

    override fun onPause() {
        closeCamera()
        bgThread?.quitSafely()
        bgThread = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera()
        else toast("Camera permission is required")
    }

    private fun closeCamera() {
        capturing = false
        curRun = null
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        previewSurface?.release()
        previewSurface = null
    }

    // ---------------------------------------------------------------- camera

    private fun readLimits() {
        val c = chars ?: return
        c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { minExp = it.lower; maxExp = it.upper }
        c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { minIso = it.lower; maxIso = it.upper }
        oisSupported = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
        val avail = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)?.toList()
        val wanted = listOf(Size(1920, 1440), Size(3264, 2448))
        val ok = wanted.filter { w -> avail == null || avail.any { it.width == w.width && it.height == w.height } }
        sizes = if (ok.isNotEmpty()) ok else listOf(avail!!.maxByOrNull { it.width * it.height }!!)
        resIdx = 0
        updateLabels()
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
        } ?: run { toast("No rear camera"); return }
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

    private fun setManual(b: CaptureRequest.Builder, expNs: Long, iso: Int, focus: Float) {
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(expNs, 33_333_333L))
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
    }

    private fun applyPreview() {
        val h = if (::bg.isInitialized) bg else return
        h.post {
            val cam = camera ?: return@post
            val s = session ?: return@post
            val ps = previewSurface ?: return@post
            if (capturing) return@post
            try {
                val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                b.addTarget(ps)
                if (manualOn) setManual(b, seekToExp(expP), seekToIso(isoP), lastFocus)
                else b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                s.setRepeatingRequest(b.build(), resultCb, bg)
            } catch (e: Exception) {
                toast("Preview error: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------- capture

    private fun setShutter(text: String, enabled: Boolean) = ui { shutter.text = text; shutter.isEnabled = enabled }
    private fun setStatus(text: String) = ui { status.text = text }

    private fun onShutter() {
        if (camera == null || session == null) return
        if (capturing) {
            bg.post { curRun?.let { finishRun(it) } }
            return
        }
        setShutter("Starting...", false)
        bg.post { beginRun() }
    }

    private fun beginRun() {
        val m = MODES[modeIdx]
        val cam = camera
        val s = session
        val rd = reader
        val ps = previewSurface
        if (cam == null || s == null || rd == null || ps == null) { setShutter("Shoot", true); return }
        if (m.kind == Kind.CLONE && rd.width > 2000) {
            setStatus("Clone supports 1920x1440 only for now. Change resolution.")
            setShutter("Shoot", true)
            return
        }
        val needManual = manualOn || m.kind != Kind.PHOTO
        var expNs = lastExpNs
        var iso = lastIso
        val fixed = m.fixedExpMs
        val cap = m.expCapMs
        if (manualOn) {
            expNs = seekToExp(expP); iso = seekToIso(isoP)
        } else if (fixed != null) {
            expNs = (fixed * 1e6).toLong(); iso = m.fixedIso
        } else if (cap != null) {
            val capNs = (cap * 1e6).toLong()
            if (expNs > capNs) { iso = (iso.toDouble() * expNs / capNs).toInt(); expNs = capNs }
        }
        expNs = expNs.coerceIn(minExp, maxExp)
        iso = iso.coerceIn(minIso, maxIso)
        try {
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(ps)
            b.addTarget(rd.surface)
            if (needManual) {
                setManual(b, expNs, iso, if (m.infinity) 0f else lastFocus)
                b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
            } else {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            if (oisOn && oisSupported) {
                b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
            }
            val r = Run(m.kind, rd.width, rd.height, if (m.kind == Kind.PHOTO && !manualOn) 5 else 2)
            curRun = r
            s.setRepeatingRequest(b.build(), resultCb, bg)
            capturing = true
            when (m.kind) {
                Kind.PHOTO -> setStatus("Capturing...")
                Kind.AVERAGE, Kind.LIGHTEN -> {
                    setStatus("Stacking... 0 frames")
                    bg.postDelayed({ finishRun(r) }, durSec * 1000L)
                }
                Kind.CLONE -> { setStatus("Get in position. Shot 1 in $durSec s"); scheduleCloneShot(r) }
            }
            setShutter(if (m.kind == Kind.PHOTO) "Shoot" else "Stop and save", m.kind != Kind.PHOTO)
        } catch (e: Exception) {
            toast("Capture error: ${e.message}")
            setShutter("Shoot", true)
        }
    }

    private fun scheduleCloneShot(r: Run) {
        bg.postDelayed({ if (curRun === r) r.want = true }, durSec * 1000L)
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
                if (r.frames % 3 == 0) setStatus("Stacking... ${r.frames} frames")
            }
            Kind.LIGHTEN -> {
                Yuv.toArgb(img, r.scratch)
                r.lt!!.add(r.scratch)
                r.frames++
                if (r.frames % 3 == 0) setStatus("Stacking... ${r.frames} frames")
            }
            Kind.CLONE -> {
                if (!r.want) return
                r.want = false
                Yuv.toArgb(img, r.scratch)
                r.cloneFrames.add(r.scratch.copyOf())
                if (r.cloneFrames.size >= CLONE_SHOTS) {
                    finishRun(r)
                } else {
                    setStatus("Shot ${r.cloneFrames.size}/$CLONE_SHOTS taken. Move! Next in $durSec s")
                    scheduleCloneShot(r)
                }
            }
        }
    }

    private fun finishRun(r: Run) {
        if (curRun !== r) return
        capturing = false
        curRun = null
        setShutter("Processing...", false)
        setStatus("Processing...")
        applyPreview()
        try {
            val argb: IntArray? = when (r.kind) {
                Kind.PHOTO -> r.scratch
                Kind.AVERAGE -> if (r.frames > 0) r.avg!!.result() else null
                Kind.LIGHTEN -> if (r.frames > 0) r.lt!!.mx else null
                Kind.CLONE -> if (r.cloneFrames.size >= 2) Clone.composite(r.cloneFrames, r.w, r.h) else null
            }
            if (argb == null) {
                setStatus("Nothing to save (too few frames).")
            } else if (save(argb, r.w, r.h, MODES[modeIdx].label.replace(" ", "").replace("/", ""))) {
                setStatus("Saved to Pictures/StackCam (${r.w}x${r.h}, ${max(r.frames, r.cloneFrames.size)} frames)")
            } else {
                setStatus("Save failed")
            }
        } catch (e: Throwable) {
            setStatus("Processing failed: ${e.javaClass.simpleName} ${e.message}")
        }
        setShutter("Shoot", true)
    }

    private fun save(argb: IntArray, w: Int, h: Int, tag: String): Boolean {
        val bmp = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        val name = "StackCam_" + tag + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StackCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return false
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        cv.clear()
        cv.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, cv, null, null)
        bmp.recycle()
        return true
    }
}
