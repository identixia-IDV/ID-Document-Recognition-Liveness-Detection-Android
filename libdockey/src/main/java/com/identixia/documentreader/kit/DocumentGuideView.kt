package com.identixia.documentreader.kit


import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat


/**
 * Passport-ratio guide plus optional locate corners.
 */
open class DocumentGuideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {


    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ix_overlay)
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.ix_accent)
    }
    private val path = Path()


    /** Detected document corners in view coordinates (LT, RT, RB, LB), or null. */
    private var detected: Array<PointF>? = null


    var locked: Boolean = false
        set(value) {
            field = value
            strokePaint.color = ContextCompat.getColor(
                context,
                if (value) R.color.ix_accent else R.color.ix_muted
            )
            strokePaint.strokeWidth = if (value) 10f else 6f
            invalidate()
        }


    fun clearDetection() {
        detected = null
        invalidate()
    }


    /** Four corners in this view's pixel space, or null / empty to hide. */
    fun setDetectedCorners(corners: List<PointF>?) {
        detected = if (corners != null && corners.size >= 4 && isUsableQuad(corners)) {
            Array(4) { i -> PointF(corners[i].x, corners[i].y) }
        } else {
            null
        }
        invalidate()
    }


    private fun isUsableQuad(corners: List<PointF>): Boolean {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in corners) {
            if (!p.x.isFinite() || !p.y.isFinite()) return false
            minX = minOf(minX, p.x)
            minY = minOf(minY, p.y)
            maxX = maxOf(maxX, p.x)
            maxY = maxOf(maxY, p.y)
        }
        return (maxX - minX) >= 24f && (maxY - minY) >= 24f
    }


    fun guideRect(): RectF = passportGuideRect(width.toFloat(), height.toFloat())

    companion object {
        fun passportGuideRect(w: Float, h: Float): RectF {
            if (w <= 0f || h <= 0f) return RectF()
            val ratio = 125f / 88f
            var fw = w * 0.86f
            var fh = fw / ratio
            if (fh > h * 0.72f) {
                fh = h * 0.72f
                fw = fh * ratio
            }
            val left = (w - fw) / 2f
            val top = (h - fh) / 2f
            return RectF(left, top, left + fw, top + fh)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val frame = passportGuideRect(w, h)
        path.reset()
        path.addRect(frame, Path.Direction.CW)

        val corners = detected
        if (corners != null) {
            path.reset()
            path.moveTo(corners[0].x, corners[0].y)
            for (i in 1 until corners.size) {
                path.lineTo(corners[i].x, corners[i].y)
            }
            path.close()
        }

        val sc = canvas.saveLayer(0f, 0f, w, h, null)
        canvas.drawRect(0f, 0f, w, h, dimPaint)
        canvas.drawPath(path, clearPaint)
        canvas.restoreToCount(sc)
        canvas.drawPath(path, strokePaint)
    }
}
