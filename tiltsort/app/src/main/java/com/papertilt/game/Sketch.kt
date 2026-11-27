package com.papertilt.game

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Hand-drawn looking strokes: every shape is drawn twice with slightly different wobble.
 *
 * The `zoom` argument is for a canvas that has been scaled up (the tray is drawn in its own
 * units). Before Android 9 a stroked path on a scaled, hardware-accelerated canvas is
 * rasterised at 1:1 and then stretched, which blurs it. Passing the canvas's zoom makes the
 * stroke scale the path itself and draw it with the canvas zoom undone, which is sharp on
 * every version.
 */
object Sketch {
    private val path = Path()
    private val matrix = Matrix()
    private val px = FloatArray(12)
    private val py = FloatArray(12)

    /** Deterministic noise in -0.5..0.5, so a shape keeps its wobble from frame to frame. */
    fun rnd(seed: Int, i: Int): Float {
        var h = seed * 374761393 + i * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f - 0.5f
    }

    private fun stroke(c: Canvas, p: Paint, zoom: Float) {
        if (zoom == 1f) {
            c.drawPath(path, p)
            return
        }
        matrix.setScale(zoom, zoom)
        path.transform(matrix)
        val width = p.strokeWidth
        p.strokeWidth = width * zoom
        c.save()
        c.scale(1f / zoom, 1f / zoom)
        c.drawPath(path, p)
        c.restore()
        p.strokeWidth = width
    }

    fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, p: Paint, seed: Int, wob: Float, zoom: Float = 1f) {
        val len = hypot(x2 - x1, y2 - y1)
        if (len < 1e-3f) return
        val nx = -(y2 - y1) / len
        val ny = (x2 - x1) / len
        for (pass in 0..1) {
            val s = seed + pass * 31
            path.rewind()
            path.moveTo(x1 + rnd(s, 0) * wob, y1 + rnd(s, 1) * wob)
            val a = rnd(s, 2) * wob * 2f
            val b = rnd(s, 3) * wob * 2f
            path.cubicTo(
                x1 + (x2 - x1) * 0.33f + nx * a, y1 + (y2 - y1) * 0.33f + ny * a,
                x1 + (x2 - x1) * 0.66f + nx * b, y1 + (y2 - y1) * 0.66f + ny * b,
                x2 + rnd(s, 4) * wob, y2 + rnd(s, 5) * wob,
            )
            stroke(c, p, zoom)
        }
    }

    /** [wob] is a fraction of the radius. */
    fun circle(c: Canvas, cx: Float, cy: Float, r: Float, p: Paint, seed: Int, wob: Float, zoom: Float = 1f) {
        val n = px.size
        for (pass in 0..1) {
            val s = seed + pass * 31
            val a0 = rnd(s, 99) * 6f
            for (i in 0 until n) {
                val a = a0 + i * 2f * PI.toFloat() / n
                val rr = r * (1f + rnd(s, i) * wob)
                px[i] = cx + cos(a) * rr
                py[i] = cy + sin(a) * rr
            }
            path.rewind()
            path.moveTo((px[0] + px[1]) / 2, (py[0] + py[1]) / 2)
            for (i in 1..n) {
                val j = i % n
                val k = (i + 1) % n
                path.quadTo(px[j], py[j], (px[j] + px[k]) / 2, (py[j] + py[k]) / 2)
            }
            path.close()
            stroke(c, p, zoom)
        }
    }

    fun rect(c: Canvas, l: Float, t: Float, r: Float, b: Float, p: Paint, seed: Int, wob: Float) {
        line(c, l, t, r, t, p, seed, wob)
        line(c, r, t, r, b, p, seed + 7, wob)
        line(c, r, b, l, b, p, seed + 13, wob)
        line(c, l, b, l, t, p, seed + 19, wob)
    }

    /** A plain, straight-edged outline. A path effect on [p] must be given in zoomed units. */
    fun box(c: Canvas, l: Float, t: Float, r: Float, b: Float, p: Paint, zoom: Float = 1f) {
        path.rewind()
        path.moveTo(l, t)
        path.lineTo(r, t)
        path.lineTo(r, b)
        path.lineTo(l, b)
        path.close()
        stroke(c, p, zoom)
    }

    fun star(c: Canvas, cx: Float, cy: Float, r: Float, p: Paint) {
        path.rewind()
        for (i in 0 until 10) {
            val a = -PI.toFloat() / 2 + i * PI.toFloat() / 5
            val rr = if (i % 2 == 0) r else r * 0.45f
            val x = cx + cos(a) * rr
            val y = cy + sin(a) * rr
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, p)
    }
}
