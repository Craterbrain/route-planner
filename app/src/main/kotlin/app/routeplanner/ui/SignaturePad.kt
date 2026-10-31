package app.routeplanner.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import com.google.android.material.R as M
import app.routeplanner.color
import java.io.File

/** Finger-drawn signature. Shows theme colors, but exports black ink on white so it prints and reads anywhere. */
class SignaturePad(ctx: Context) : View(ctx) {
    private val strokes = mutableListOf<Path>()
    private var current: Path? = null
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = ctx.color(M.attr.colorOnSurface)
    }
    private val line = Paint().apply { color = ctx.color(M.attr.colorOutline); strokeWidth = resources.displayMetrics.density }
    private val bg = ctx.color(M.attr.colorSurfaceVariant)

    val isEmpty get() = strokes.isEmpty() && current == null
    var onChange: (() -> Unit)? = null

    fun clear() { strokes.clear(); current = null; invalidate(); onChange?.invoke() }

    override fun onDraw(c: Canvas) {
        c.drawColor(bg)
        val y = height * 0.75f
        c.drawLine(width * 0.06f, y, width * 0.94f, y, line)
        strokes.forEach { c.drawPath(it, ink) }
        current?.let { c.drawPath(it, ink) }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                current = Path().apply { moveTo(e.x, e.y); lineTo(e.x + 0.1f, e.y + 0.1f) }
            }
            MotionEvent.ACTION_MOVE -> current?.let { p ->
                for (i in 0 until e.historySize) p.lineTo(e.getHistoricalX(i), e.getHistoricalY(i))
                p.lineTo(e.x, e.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current?.let { strokes += it; current = null; onChange?.invoke() }
                if (e.actionMasked == MotionEvent.ACTION_UP) performClick() // accessibility services expect a click for a completed touch
            }
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    /** Writes a PNG of the signature; false if nothing was drawn or the write failed. */
    fun savePng(file: File): Boolean {
        if (isEmpty || width == 0 || height == 0) return false
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint(ink).apply { color = Color.BLACK }
        strokes.forEach { c.drawPath(it, p) }
        return runCatching { file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }.getOrDefault(false)
            .also { bmp.recycle() }
    }
}
