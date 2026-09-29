package com.identixia.documentreader

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.identixia.documentreadersdk.DocumentReaderSDK
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Live preview using [DocumentReaderSDK.locateDocument] (bounds + type `score`, no OCR).
 * User taps Capture when ready; then [DocSdkSession.startGallery] + [DocumentReaderSDK.recognize].
 */
class CameraActivity : AppCompatActivity() {

    companion object {
        /** Draw overlay when locate `score` ≥ this (percent). */
        private const val SHOW_THRESHOLD = 50
        /** Strong lock / still refresh when locate `score` ≥ this. */
        private const val HIGH_THRESHOLD = 85
        /** Capture enabled at this locate `score`. */
        private const val KEEP_CAPTURE_MIN = 50
        /** Max edge for locate bitmaps (keeps overlay latency down). */
        private const val LOCATE_MAX_EDGE = 480
    }

    private lateinit var previewView: PreviewView
    private lateinit var guideView: DocumentGuideView
    private lateinit var txtPercent: TextView
    private lateinit var txtHint: TextView
    private lateinit var btnCapture: Button
    private lateinit var cameraExecutor: ExecutorService

    private val locating = AtomicBoolean(false)
    private val captured = AtomicBoolean(false)
    private var lastStillUpdateMs = 0L
    private var latestStill: Bitmap? = null
    private val stillLock = Any()
    private var cameraProvider: ProcessCameraProvider? = null

    // TEMPORARY CROP PREVIEW — start
    // Delete only this block when asked to "Delete temporary preview".
    private var cropPreviewWrap: LinearLayout? = null
    private var cropPreviewImage: ImageView? = null
    private var cropPreviewBmp: Bitmap? = null
    // TEMPORARY CROP PREVIEW — end

    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.previewView)
        previewView.implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        guideView = findViewById(R.id.guideView)
        txtPercent = findViewById(R.id.txtPercent)
        txtHint = findViewById(R.id.txtCameraHint)
        btnCapture = findViewById(R.id.btnCapture)
        btnCapture.visibility = View.VISIBLE
        btnCapture.isEnabled = false
        btnCapture.setOnClickListener { onCaptureClicked() }
        findViewById<ImageButton>(R.id.btnCloseCamera).setOnClickListener { finish() }
        cameraExecutor = Executors.newSingleThreadExecutor()

        previewView.post {
            if (isDestroyed) return@post
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                startCamera()
            } else {
                permission.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider

            val previewSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .build()
            val preview = Preview.Builder()
                .setResolutionSelector(previewSelector)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysisSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    )
                )
                .build()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(analysisSelector)
                .build()
            analysis.setAnalyzer(cameraExecutor, this::analyzeFrame)

            provider.unbindAll()
            val viewPort = previewView.viewPort
            if (viewPort != null) {
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    UseCaseGroup.Builder()
                        .setViewPort(viewPort)
                        .addUseCase(preview)
                        .addUseCase(analysis)
                        .build(),
                )
            } else {
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onCaptureClicked() {
        if (captured.get()) return
        val still = synchronized(stillLock) { latestStill }
        if (still == null) {
            Toast.makeText(this, R.string.camera_hint, Toast.LENGTH_SHORT).show()
            return
        }
        beginRecognize(still)
    }

    private fun analyzeFrame(image: ImageProxy) {
        if (captured.get()) {
            image.close()
            return
        }
        if (!locating.compareAndSet(false, true)) {
            image.close()
            return
        }

        val rotation = image.imageInfo.rotationDegrees
        val sensorCrop = Rect(image.cropRect)
        val frame = try {
            image.toBitmap()
        } catch (_: Throwable) {
            null
        } finally {
            image.close()
        }

        if (frame == null) {
            locating.set(false)
            return
        }

        var stillHeld = false
        var locateBmp: Bitmap? = null
        var upright: Bitmap? = null
        var cropped: Bitmap? = null
        var visible: Bitmap? = null
        try {
            val viewW = previewView.width
            val viewH = previewView.height
            if (viewW <= 0 || viewH <= 0) return

            // cropRect is the PreviewView hole (pre-rotation). Cut that first so
            // the still is not a wider FOV than the overlay rectangle.
            visible = cropToProxyRect(frame, sensorCrop)
            if (visible !== frame && !frame.isRecycled) frame.recycle()
            upright = rotateToUpright(visible, rotation)
            cropped = cropToGuide(upright, viewW, viewH)
            if (cropped == null) return

            locateBmp = scaleMax(cropped, LOCATE_MAX_EDGE)
            val locateJson = try {
                DocumentReaderSDK.locateDocument(locateBmp)
            } catch (_: Throwable) {
                "{\"msg\":\"locate failed\"}"
            }
            val scorePct = ResultParser.documentPercent(locateJson)
            val cornersLocate = ResultParser.documentCorners(locateJson)
            val showOverlay = scorePct >= SHOW_THRESHOLD && cornersLocate != null
            val high = scorePct >= HIGH_THRESHOLD
            val now = System.currentTimeMillis()
            if (scorePct >= KEEP_CAPTURE_MIN && (high || now - lastStillUpdateMs > 500)) {
                synchronized(stillLock) {
                    if (!captured.get()) {
                        if (latestStill !== cropped) latestStill?.recycle()
                        latestStill = cropped
                        stillHeld = true
                    }
                }
                lastStillUpdateMs = now
            }

            val cropW = cropped.width
            val cropH = cropped.height
            val locateW = locateBmp.width.toFloat().coerceAtLeast(1f)
            val locateH = locateBmp.height.toFloat().coerceAtLeast(1f)
            val viewCorners = if (showOverlay && cornersLocate != null) {
                mapCropCornersToGuide(cornersLocate, cropW, cropH, locateW, locateH, viewW, viewH)
            } else {
                null
            }
            // TEMPORARY CROP PREVIEW — start
            // Delete only this block when asked to "Delete temporary preview".
            val previewCopy = if (stillHeld) {
                try { cropped.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Throwable) { null }
            } else {
                null
            }
            // TEMPORARY CROP PREVIEW — end

            runOnUiThread {
                if (captured.get()) {
                    // TEMPORARY CROP PREVIEW — start
                    previewCopy?.takeIf { !it.isRecycled }?.recycle()
                    // TEMPORARY CROP PREVIEW — end
                    return@runOnUiThread
                }
                txtPercent.text = "$scorePct%"
                if (viewCorners != null) guideView.setDetectedCorners(viewCorners)
                else guideView.clearDetection()
                guideView.locked = high
                btnCapture.visibility = View.VISIBLE
                btnCapture.isEnabled = scorePct >= KEEP_CAPTURE_MIN
                txtHint.setText(if (scorePct >= KEEP_CAPTURE_MIN) R.string.camera_ready else R.string.camera_hint)
                // TEMPORARY CROP PREVIEW — start
                // Delete only this block when asked to "Delete temporary preview".
                if (previewCopy != null) showCropPreview(previewCopy)
                // TEMPORARY CROP PREVIEW — end
            }
        } finally {
            val scaled = locateBmp
            if (scaled != null && scaled !== cropped && scaled !== upright && scaled !== visible && scaled !== frame && !scaled.isRecycled) {
                scaled.recycle()
            }
            if (cropped != null && !stillHeld && cropped !== upright && cropped !== visible && !cropped.isRecycled) {
                cropped.recycle()
            }
            if (upright != null && upright !== cropped && upright !== visible && !upright.isRecycled) {
                upright.recycle()
            }
            if (visible != null && visible !== cropped && !visible.isRecycled) {
                visible.recycle()
            }
            locating.set(false)
        }
    }

    private fun beginRecognize(still: Bitmap) {
        if (!captured.compareAndSet(false, true)) return
        runOnUiThread {
            btnCapture.visibility = View.GONE
            txtHint.setText(R.string.camera_capturing)
            // Unbind to freeze the preview in the background
            cameraProvider?.unbindAll()
        }
        cameraExecutor.execute {
            val (json, deny) = try {
                // latestStill is already cropped to the on-screen rectangle.
                DocSdkSession.startGallery()
                DocSdkSession.recognize(this, still, null)
            } catch (t: Throwable) {
                "{\"msg\":\"${t.message}\"}" to null
            }
            runOnUiThread {
                if (!deny.isNullOrBlank()) {
                    Toast.makeText(this, deny, Toast.LENGTH_LONG).show()
                }
                ResultActivity.open(this, json)
                finish()
            }
        }
    }

    private fun mapCropCornersToGuide(
        corners: List<Pair<Float, Float>>,
        cropW: Int,
        cropH: Int,
        locateW: Float,
        locateH: Float,
        viewW: Int,
        viewH: Int
    ): List<PointF>? {
        if (corners.size < 4 || cropW <= 0 || cropH <= 0) return null
        val guide = DocumentGuideView.passportGuideRect(viewW.toFloat(), viewH.toFloat())
        if (guide.width() <= 1f || guide.height() <= 1f) return null
        val sx = cropW / locateW
        val sy = cropH / locateH
        return List(4) { i ->
            val cx = corners[i].first * sx
            val cy = corners[i].second * sy
            PointF(
                guide.left + cx * guide.width() / cropW,
                guide.top + (cropH - cy) * guide.height() / cropH
            )
        }
    }

    /** Map the on-screen passport rectangle onto the upright still (PreviewView FILL_CENTER). */
    private fun cropToGuide(still: Bitmap, viewW: Int, viewH: Int): Bitmap? {
        if (viewW <= 0 || viewH <= 0 || still.width < 8 || still.height < 8) return null
        val guide = DocumentGuideView.passportGuideRect(viewW.toFloat(), viewH.toFloat())
        val src = mapViewRectToBitmap(guide, viewW, viewH, still.width, still.height)
        if (src.width() < 32 || src.height() < 32) return null
        return Bitmap.createBitmap(still, src.left, src.top, src.width(), src.height())
    }

    /**
     * [cropRect] is in the un-rotated ImageProxy. Cut it before [rotateToUpright]
     * so the still matches the PreviewView hole instead of the full sensor buffer.
     */
    private fun cropToProxyRect(src: Bitmap, crop: Rect): Bitmap {
        val left = crop.left.coerceIn(0, src.width - 1)
        val top = crop.top.coerceIn(0, src.height - 1)
        val width = crop.width().coerceAtMost(src.width - left).coerceAtLeast(1)
        val height = crop.height().coerceAtMost(src.height - top).coerceAtLeast(1)
        if (left == 0 && top == 0 && width == src.width && height == src.height) return src
        return Bitmap.createBitmap(src, left, top, width, height)
    }

    private fun mapViewRectToBitmap(
        viewRect: RectF,
        viewW: Int,
        viewH: Int,
        imageW: Int,
        imageH: Int
    ): Rect {
        val scale = max(viewW.toFloat() / imageW, viewH.toFloat() / imageH)
        val dx = (viewW - imageW * scale) / 2f
        val dy = (viewH - imageH * scale) / 2f
        val left = ((viewRect.left - dx) / scale).toInt().coerceIn(0, imageW - 1)
        val top = ((viewRect.top - dy) / scale).toInt().coerceIn(0, imageH - 1)
        val right = ((viewRect.right - dx) / scale).toInt().coerceIn(left + 1, imageW)
        val bottom = ((viewRect.bottom - dy) / scale).toInt().coerceIn(top + 1, imageH)
        return Rect(left, top, right, bottom)
    }

    private fun rotateToUpright(src: Bitmap, rotation: Int): Bitmap {
        val rot = ((rotation % 360) + 360) % 360
        if (rot == 0) return src
        val m = Matrix().apply { postRotate(rot.toFloat()) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        return out
    }

    private fun scaleMax(src: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxEdge) return src
        val scale = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        synchronized(stillLock) {
            latestStill?.recycle()
            latestStill = null
        }
        // TEMPORARY CROP PREVIEW — start
        // Delete only this block when asked to "Delete temporary preview".
        cropPreviewBmp?.takeIf { !it.isRecycled }?.recycle()
        cropPreviewBmp = null
        // TEMPORARY CROP PREVIEW — end
    }

    // TEMPORARY CROP PREVIEW — start
    // Delete only this block when asked to "Delete temporary preview".
    private fun showCropPreview(copy: Bitmap) {
        if (copy.isRecycled) return
        ensureCropPreviewOverlay()
        cropPreviewBmp?.takeIf { it !== copy && !it.isRecycled }?.recycle()
        cropPreviewBmp = copy
        cropPreviewImage?.setImageBitmap(copy)
        cropPreviewWrap?.visibility = View.VISIBLE
    }

    private fun ensureCropPreviewOverlay() {
        if (cropPreviewWrap != null) return
        val parent = previewView.parent as? FrameLayout ?: return
        val label = TextView(this).apply {
            text = "Crop preview (temporary)"
            setTextColor(Color.WHITE)
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply {
                setColor(0x99000000.toInt())
                setStroke(dip(2), 0xFFF59E0B.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(dip(148), dip(104))
        }
        cropPreviewImage = image
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            visibility = View.GONE
            addView(label)
            addView(image)
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END,
        ).apply {
            rightMargin = dip(12)
            bottomMargin = dip(12)
        }
        parent.addView(wrap, lp)
        cropPreviewWrap = wrap
    }

    private fun dip(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
    }
    // TEMPORARY CROP PREVIEW — end
}
