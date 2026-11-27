package com.papertilt.game.core

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Ball and hole sizes. A ball drops into any hole at least its own size. */
enum class Size(val ballR: Float, val holeR: Float) {
    S(3.2f, 4.7f),
    M(4.7f, 6.3f),
    L(6.3f, 8.1f),
}

enum class BallState { SPAWNING, ROLLING, SINKING, FALLING, HOME, GONE }

enum class EvType { BUMP, SINK_OK, SINK_BAD, FALL }

class Event(val type: EvType, val ball: Ball)

class Ball(var x: Float, var y: Float, val size: Size, val id: Int) {
    var vx = 0f
    var vy = 0f
    var state = BallState.ROLLING
    var t = 0f
    var spawnX = x
    var spawnY = y
    var hole: Hole? = null
    var sunkOk = false
    var roll = 0f
    val r: Float get() = size.ballR

    fun respawn() {
        x = spawnX
        y = spawnY
        vx = 0f
        vy = 0f
        hole = null
        sunkOk = false
        t = 0f
        state = BallState.SPAWNING
    }
}

/** A hole; with a non-zero amplitude it slides back and forth along a line. */
class Hole(
    val baseX: Float,
    val baseY: Float,
    val size: Size,
    val ampX: Float = 0f,
    val ampY: Float = 0f,
    val period: Float = 5f,
    val phase: Float = 0f,
) {
    var x = baseX
    var y = baseY
    var filled = false
    val r: Float get() = size.holeR
    val moving: Boolean get() = ampX != 0f || ampY != 0f

    fun update(time: Float) {
        if (!moving) return
        val s = sin(2.0 * PI * (time / period + phase)).toFloat()
        x = baseX + ampX * s
        y = baseY + ampY * s
    }
}

class Wall(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

class Zone(val x: Float, val y: Float, val w: Float, val h: Float) {
    fun contains(px: Float, py: Float) = px >= x && px <= x + w && py >= y && py <= y + h
}

/**
 * The tray and everything on it. Units are abstract: the tray is [w] x [h],
 * the origin is the top-left corner and y grows downwards (like the screen).
 * A ball whose centre leaves the tray falls off; walls are the only barrier.
 */
class World(val w: Float, val h: Float, val holesFill: Boolean = true) {
    val balls = ArrayList<Ball>()
    val holes = ArrayList<Hole>()
    val walls = ArrayList<Wall>()
    val sand = ArrayList<Zone>()
    val events = ArrayList<Event>()
    var time = 0f

    /** How hard tilt pushes the balls here, as a multiple of [ACC]. Later levels push harder. */
    var push = 1f

    /** How much drag the paper has, as a multiple of [FRICTION]. Later levels are more slippery. */
    var grip = 1f

    /** Which edges have a rail: top, right, bottom, left. The others are open. */
    val railed = BooleanArray(4)

    fun rails(top: Boolean, right: Boolean, bottom: Boolean, left: Boolean) {
        railed[0] = top
        railed[1] = right
        railed[2] = bottom
        railed[3] = left
        if (top) walls.add(Wall(0f, 0f, w, 0f))
        if (right) walls.add(Wall(w, 0f, w, h))
        if (bottom) walls.add(Wall(0f, h, w, h))
        if (left) walls.add(Wall(0f, 0f, 0f, h))
    }

    /** Advance by [dt] seconds; ([tx], [ty]) is the tilt as a fraction of g. */
    fun step(dt: Float, tx: Float, ty: Float) {
        time += dt
        for (h in holes) h.update(time)
        for (i in balls.indices) {
            val b = balls[i]
            when (b.state) {
                BallState.SPAWNING -> {
                    b.t += dt
                    // Wait for the spot to clear, so a ball never pops in on top of another.
                    if (b.t >= SPAWN_T && spotClear(b)) {
                        b.state = BallState.ROLLING
                        b.t = 0f
                    }
                }
                BallState.ROLLING -> roll(b, dt, tx, ty)
                BallState.SINKING -> {
                    b.t += dt
                    val h = b.hole
                    if (h != null) {
                        val k = min(1f, dt * 14f)
                        b.x += (h.x - b.x) * k
                        b.y += (h.y - b.y) * k
                    }
                    if (b.t >= SINK_T) {
                        b.state = if (b.sunkOk && holesFill) BallState.HOME else BallState.GONE
                        b.t = 0f
                    }
                }
                BallState.FALLING -> {
                    b.t += dt
                    b.x += b.vx * dt
                    b.y += b.vy * dt
                    if (b.t >= FALL_T) b.state = BallState.GONE
                }
                BallState.HOME -> {
                    val h = b.hole
                    if (h != null) {
                        b.x = h.x
                        b.y = h.y
                    }
                }
                BallState.GONE -> {}
            }
        }
        collideBalls()
    }

    private fun spotClear(b: Ball): Boolean {
        for (o in balls) {
            if (o !== b && o.state == BallState.ROLLING && hypot(o.x - b.x, o.y - b.y) < o.r + b.r) return false
        }
        return true
    }

    /** True when [b] is rolling close to something that would cost an out. */
    fun inDanger(b: Ball): Boolean {
        if (b.state != BallState.ROLLING) return false
        if (!railed[0] && b.y < EDGE_WARN || !railed[2] && b.y > h - EDGE_WARN) return true
        if (!railed[3] && b.x < EDGE_WARN || !railed[1] && b.x > w - EDGE_WARN) return true
        for (hl in holes) {
            if (hl.filled || hl.size.ordinal <= b.size.ordinal) continue
            if (hypot(hl.x - b.x, hl.y - b.y) < hl.r + b.r * 0.3f + 2.5f) return true
        }
        return false
    }

    private fun roll(b: Ball, dt: Float, tx: Float, ty: Float) {
        b.vx += tx * ACC * push * dt
        b.vy += ty * ACC * push * dt
        var fr = FRICTION * grip
        for (z in sand) if (z.contains(b.x, b.y)) fr = SAND_FRICTION
        val k = max(0f, 1f - fr * dt)
        b.vx *= k
        b.vy *= k

        for (h in holes) {
            if (h.filled || b.size.ordinal > h.size.ordinal) continue
            val dx = h.x - b.x
            val dy = h.y - b.y
            val d = hypot(dx, dy)
            if (d < h.r - b.r * 0.45f) {
                val ok = b.size == h.size
                b.state = BallState.SINKING
                b.t = 0f
                b.hole = h
                b.sunkOk = ok
                if (ok && holesFill) h.filled = true
                events.add(Event(if (ok) EvType.SINK_OK else EvType.SINK_BAD, b))
                return
            }
            if (d < h.r + b.r * 0.3f && d > 0.001f) {
                b.vx += dx / d * RIM_PULL * dt
                b.vy += dy / d * RIM_PULL * dt
            }
        }

        val sp = hypot(b.vx, b.vy)
        if (sp > MAX_V) {
            b.vx *= MAX_V / sp
            b.vy *= MAX_V / sp
        }
        b.x += b.vx * dt
        b.y += b.vy * dt
        b.roll += sp * dt / b.r

        for (wl in walls) collideWall(b, wl)

        if (b.x < 0f || b.x > w || b.y < 0f || b.y > h) {
            b.state = BallState.FALLING
            b.t = 0f
            events.add(Event(EvType.FALL, b))
        }
    }

    private fun collideWall(b: Ball, wl: Wall) {
        val ex = wl.x2 - wl.x1
        val ey = wl.y2 - wl.y1
        val len2 = ex * ex + ey * ey
        val t = if (len2 <= 0f) 0f else (((b.x - wl.x1) * ex + (b.y - wl.y1) * ey) / len2).coerceIn(0f, 1f)
        val cx = wl.x1 + ex * t
        val cy = wl.y1 + ey * t
        val dx = b.x - cx
        val dy = b.y - cy
        val d = hypot(dx, dy)
        val minD = b.r + WALL_T
        if (d >= minD || d < 1e-4f) return
        val nx = dx / d
        val ny = dy / d
        b.x = cx + nx * minD
        b.y = cy + ny * minD
        val vn = b.vx * nx + b.vy * ny
        if (vn < 0f) {
            b.vx -= (1f + WALL_BOUNCE) * vn * nx
            b.vy -= (1f + WALL_BOUNCE) * vn * ny
            if (-vn > BUMP_SPEED) events.add(Event(EvType.BUMP, b))
        }
    }

    private fun collideBalls() {
        for (i in 0 until balls.size) {
            val a = balls[i]
            if (a.state != BallState.ROLLING) continue
            for (j in i + 1 until balls.size) {
                val b = balls[j]
                if (b.state != BallState.ROLLING) continue
                val dx = b.x - a.x
                val dy = b.y - a.y
                val d = hypot(dx, dy)
                val minD = a.r + b.r
                if (d >= minD || d < 1e-4f) continue
                val nx = dx / d
                val ny = dy / d
                val ia = 1f / (a.r * a.r)
                val ib = 1f / (b.r * b.r)
                val over = minD - d
                a.x -= nx * over * ia / (ia + ib)
                a.y -= ny * over * ia / (ia + ib)
                b.x += nx * over * ib / (ia + ib)
                b.y += ny * over * ib / (ia + ib)
                val vn = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny
                if (vn < 0f) {
                    val imp = -(1f + BALL_BOUNCE) * vn / (ia + ib)
                    a.vx -= imp * ia * nx
                    a.vy -= imp * ia * ny
                    b.vx += imp * ib * nx
                    b.vy += imp * ib * ny
                    if (-vn > BUMP_SPEED) events.add(Event(EvType.BUMP, a))
                }
            }
        }
    }

    companion object {
        const val ACC = 360f
        const val FRICTION = 1.5f
        const val SAND_FRICTION = 5.5f
        const val RIM_PULL = 90f
        const val MAX_V = 150f
        const val EDGE_WARN = 9f
        const val WALL_T = 0.8f
        const val WALL_BOUNCE = 0.45f
        const val BALL_BOUNCE = 0.6f
        const val BUMP_SPEED = 14f
        const val SPAWN_T = 0.4f
        const val SINK_T = 0.3f
        const val FALL_T = 0.45f
    }
}
