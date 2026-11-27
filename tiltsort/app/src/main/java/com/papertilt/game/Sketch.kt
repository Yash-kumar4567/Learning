package com.papertilt.game

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Hand-drawn looking strokes: every shape is drawn twice with slightly different wobble. */
object Sketch {
    private val path = Path()
    private val px = FloatArray(12)
    private val py = FloatArray(12)

    /** Deterministic noise in -0.5..0.5, so a shape keeps its wobble from frame to frame. */
    fun rnd(seed: Int, i: Int): Float {
        var h = seed * 374761393 + i * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f - 0.5f
    }

    fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, p: Paint, seed: Int, wob: Float) {
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
            c.drawPath(path, p)
        }
    }

    /** [wob] is a fraction of the radius. */
    fun circle(c: Canvas, cx: Float, cy: Float, r: Float, p: Paint, seed: Int, wob: Float) {
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
            c.drawPath(path, p)
        }
    }

    fun rect(c: Canvas, l: Float, t: Float, r: Float, b: Float, p: Paint, seed: Int, wob: Float) {
        line(c, l, t, r, t, p, seed, wob)
        line(c, r, t, r, b, p, seed + 7, wob)
        line(c, r, b, l, b, p, seed + 13, wob)
        line(c, l, b, l, t, p, seed + 19, wob)
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
