package com.papertilt.game

import com.papertilt.game.core.Ball
import com.papertilt.game.core.BallState
import com.papertilt.game.core.Hole
import com.papertilt.game.core.Levels
import com.papertilt.game.core.Size
import com.papertilt.game.core.SoloGame
import com.papertilt.game.core.World
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Test

/**
 * Empirical probe: can each of the 40 campaign levels be won, and is any of them unfair?
 *
 * A bot drives [SoloGame] with the only control a player has, one tilt vector per step
 * (each axis in -0.5..0.5, 120 steps per second). It is a model-predictive controller:
 * it picks a ball, plans a route for it on a grid (around walls, bigger holes and open
 * edges, and around places where another ball would be lost if all balls moved together),
 * and at every decision simulates a handful of candidate tilts followed by braking and
 * keeps the one that ends best. When no ball can make progress it looks for a move that
 * changes the formation (sand, a wall, or losing a ball on purpose).
 *
 * What is measured on every level:
 *  - "ideal": instant, exact tilt, eight parameter sets (ball order, margins, look-ahead).
 *    The best run is replayed on a fresh [SoloGame] to confirm the win.
 *  - "room": the ideal player again, told to keep every ball a set distance away from open
 *    edges and from bigger holes; the widest distance at which it still wins with no outs.
 *  - "careful" and "sloppy": the same brain behind a clumsy hand. Steering tilts are halved,
 *    the tilt follows the command with a lag, arrives late (and the bot does not know it is
 *    late), and wobbles. These are a stand-in for a human, not a model of one.
 *  - "naive": the crude player from CoreTest that aims straight at the hole.
 *  - Geometry checks that need no bot: calm when flat, won by one fixed tilt, spawn points,
 *    moving holes versus spawns and walls, overlapping walls, identical layouts, and how far
 *    the whole formation of balls can slide before one leaves the paper.
 *
 * Output: a table on stdout and details in app/build/solvability/. The full run takes
 * about five minutes on four cores; set PROBE_LEVELS=22,38 to look at a few levels only.
 */
class SolvabilityProbe {

    // ---------------------------------------------------------------- simulator wrapper

    private class Handicap(val name: String, val lagTau: Float, val delay: Int, val sigma: Float)

    private class Snap(nb: Int, nh: Int) {
        val bf = FloatArray(nb * 6)
        val bstate = arrayOfNulls<BallState>(nb)
        val bhole = arrayOfNulls<Hole>(nb)
        val bsunk = BooleanArray(nb)
        val resp = FloatArray(nb)
        val hx = FloatArray(nh)
        val hy = FloatArray(nh)
        val hfilled = BooleanArray(nh)
        var wtime = 0f
        var gtime = 0f
        var outs = 0
        var won = false
        var lost = false
        var steps = 0
        var ax = 0f
        var ay = 0f
    }

    private class Sim(val level: Int, val handicap: Handicap?, noiseSeed: Long) {
        val g = SoloGame(level)
        val w: World = g.world
        val balls: List<Ball> = w.balls
        val holes: List<Hole> = w.holes
        val nb = balls.size
        val nh = holes.size
        var steps = 0
        var logging = true
        var acts = FloatArray(8192)
        val outNotes = ArrayList<Triple<Int, Int, String>>()
        val homeNotes = ArrayList<Pair<Int, String>>()

        // the clumsy hand
        var ax = 0f
        var ay = 0f
        private val alpha = if (handicap == null) 1f else min(1f, DT / handicap.lagTau)
        private val qx = FloatArray(max(1, handicap?.delay ?: 1))
        private val qy = FloatArray(max(1, handicap?.delay ?: 1))
        private var qi = 0
        private var nx = 0f
        private var ny = 0f
        private val noise = java.util.Random(noiseSeed)

        @Suppress("UNCHECKED_CAST")
        private val respawn: HashMap<Ball, Float> =
            SoloGame::class.java.getDeclaredField("respawn").apply { isAccessible = true }.get(g) as HashMap<Ball, Float>

        fun step(cx: Float, cy: Float) {
            var tx = cx
            var ty = cy
            val hc = handicap
            if (hc != null) {
                var gx = cx
                var gy = cy
                if (logging) {
                    if (hc.delay > 0) {
                        val ox = qx[qi]
                        val oy = qy[qi]
                        qx[qi] = cx
                        qy[qi] = cy
                        qi = (qi + 1) % hc.delay
                        gx = ox
                        gy = oy
                    }
                    if (hc.sigma > 0f) {
                        val k = DT / NOISE_TAU
                        val s = hc.sigma * sqrt(2f * k)
                        nx += -nx * k + s * noise.nextGaussian().toFloat()
                        ny += -ny * k + s * noise.nextGaussian().toFloat()
                        gx += nx
                        gy += ny
                    }
                }
                ax = (ax + (gx - ax) * alpha).coerceIn(-0.5f, 0.5f)
                ay = (ay + (gy - ay) * alpha).coerceIn(-0.5f, 0.5f)
                tx = ax
                ty = ay
            }
            // the app's sensor code zeroes anything inside its dead zone
            if (abs(tx) < DEAD_ZONE) tx = 0f
            if (abs(ty) < DEAD_ZONE) ty = 0f
            if (logging) {
                if (steps * 2 + 2 > acts.size) acts = acts.copyOf(acts.size * 2)
                acts[steps * 2] = tx
                acts[steps * 2 + 1] = ty
            }
            val outsBefore = g.outs
            g.step(DT, tx, ty)
            g.fx.clear()
            steps++
            if (logging) {
                for (b in balls) {
                    if (b.state == BallState.SINKING && b.sunkOk && b.t == 0f) {
                        homeNotes.add(steps to "t=%.1fs ball %d (%s) home in hole %d; others at %s".format(
                            steps * DT, b.id, b.size, holes.indexOf(b.hole),
                            balls.filter { it.state == BallState.ROLLING }.joinToString(" ") { "%d(%.0f,%.0f)".format(it.id, it.x, it.y) }))
                    }
                }
            }
            if (logging && g.outs > outsBefore) {
                for (b in balls) {
                    if (b.t != 0f) continue
                    if (b.state == BallState.FALLING) {
                        outNotes.add(Triple(steps, b.id, "t=%.1fs ball %d (%s) rolled off the paper at (%.0f, %.0f)".format(steps * DT, b.id, b.size, b.x, b.y)))
                    } else if (b.state == BallState.SINKING && !b.sunkOk) {
                        val h = b.hole
                        val hi = holes.indexOf(h)
                        outNotes.add(Triple(steps, b.id, "t=%.1fs ball %d (%s) lost in %s hole %d%s".format(steps * DT, b.id, b.size, h?.size, hi, if (h?.moving == true) " (moving)" else "")))
                    }
                }
            }
        }

        fun homes(): Int {
            var n = 0
            for (b in balls) if (b.state == BallState.HOME || (b.state == BallState.SINKING && b.sunkOk)) n++
            return n
        }

        fun maxSpeed(): Float {
            var m = 0f
            for (b in balls) if (b.state == BallState.ROLLING) m = max(m, hypot(b.vx, b.vy))
            return m
        }

        fun save(s: Snap) {
            for (i in 0 until nb) {
                val b = balls[i]
                val k = i * 6
                s.bf[k] = b.x; s.bf[k + 1] = b.y; s.bf[k + 2] = b.vx; s.bf[k + 3] = b.vy; s.bf[k + 4] = b.t; s.bf[k + 5] = b.roll
                s.bstate[i] = b.state
                s.bhole[i] = b.hole
                s.bsunk[i] = b.sunkOk
                s.resp[i] = if (respawn.isEmpty()) Float.NaN else (respawn[b] ?: Float.NaN)
            }
            for (i in 0 until nh) {
                val h = holes[i]
                s.hx[i] = h.x; s.hy[i] = h.y; s.hfilled[i] = h.filled
            }
            s.wtime = w.time; s.gtime = g.time; s.outs = g.outs; s.won = g.won; s.lost = g.lost; s.steps = steps
            s.ax = ax; s.ay = ay
        }

        fun load(s: Snap) {
            respawn.clear()
            for (i in 0 until nb) {
                val b = balls[i]
                val k = i * 6
                b.x = s.bf[k]; b.y = s.bf[k + 1]; b.vx = s.bf[k + 2]; b.vy = s.bf[k + 3]; b.t = s.bf[k + 4]; b.roll = s.bf[k + 5]
                b.state = s.bstate[i]!!
                b.hole = s.bhole[i]
                b.sunkOk = s.bsunk[i]
                if (!s.resp[i].isNaN()) respawn[b] = s.resp[i]
            }
            for (i in 0 until nh) {
                val h = holes[i]
                h.x = s.hx[i]; h.y = s.hy[i]; h.filled = s.hfilled[i]
            }
            w.time = s.wtime; g.time = s.gtime; g.outs = s.outs; g.won = s.won; g.lost = s.lost; steps = s.steps
            ax = s.ax; ay = s.ay
            w.events.clear()
            if (logging) {
                while (outNotes.isNotEmpty() && outNotes.last().first > s.steps) outNotes.removeAt(outNotes.size - 1)
                while (homeNotes.isNotEmpty() && homeNotes.last().first > s.steps) homeNotes.removeAt(homeNotes.size - 1)
            }
        }
    }

    // ---------------------------------------------------------------- static geometry

    private class Geo(val w: World) {
        var railT = false
        var railR = false
        var railB = false
        var railL = false
        val inner = ArrayList<FloatArray>()
        val wallMask = Array(3) { BooleanArray(N) }

        init {
            for (wl in w.walls) {
                when {
                    wl.x1 == 0f && wl.y1 == 0f && wl.x2 == w.w && wl.y2 == 0f -> railT = true
                    wl.x1 == w.w && wl.y1 == 0f && wl.x2 == w.w && wl.y2 == w.h -> railR = true
                    wl.x1 == 0f && wl.y1 == w.h && wl.x2 == w.w && wl.y2 == w.h -> railB = true
                    wl.x1 == 0f && wl.y1 == 0f && wl.x2 == 0f && wl.y2 == w.h -> railL = true
                    else -> inner.add(floatArrayOf(wl.x1, wl.y1, wl.x2, wl.y2))
                }
            }
            for (s in 0..2) {
                val lim = Size.values()[s].ballR + World.WALL_T - 0.35f
                for (wl in w.walls) {
                    val i0 = max(0, floor(min(wl.x1, wl.x2) - lim).toInt())
                    val i1 = min(GW - 1, ceil(max(wl.x1, wl.x2) + lim).toInt())
                    val j0 = max(0, floor(min(wl.y1, wl.y2) - lim).toInt())
                    val j1 = min(GH - 1, ceil(max(wl.y1, wl.y2) + lim).toInt())
                    for (j in j0..j1) for (i in i0..i1) {
                        if (segDist(i.toFloat(), j.toFloat(), wl.x1, wl.y1, wl.x2, wl.y2) < lim) wallMask[s][j * GW + i] = true
                    }
                }
            }
        }

        val rails: Int get() = (if (railT) 1 else 0) + (if (railR) 1 else 0) + (if (railB) 1 else 0) + (if (railL) 1 else 0)

        fun railsText(): String =
            (if (railT) "T" else "-") + (if (railR) "R" else "-") + (if (railB) "B" else "-") + (if (railL) "L" else "-")
    }

    private class MinHeap(cap: Int) {
        var key = FloatArray(cap)
        var v = IntArray(cap)
        var n = 0
        var lastKey = 0f

        fun push(k: Float, x: Int) {
            if (n == key.size) {
                key = key.copyOf(n * 2)
                v = v.copyOf(n * 2)
            }
            var i = n++
            while (i > 0) {
                val p = (i - 1) ushr 1
                if (key[p] <= k) break
                key[i] = key[p]; v[i] = v[p]
                i = p
            }
            key[i] = k; v[i] = x
        }

        fun pop(): Int {
            val top = v[0]
            lastKey = key[0]
            n--
            if (n > 0) {
                val k = key[n]
                val x = v[n]
                var i = 0
                while (true) {
                    var c = 2 * i + 1
                    if (c >= n) break
                    if (c + 1 < n && key[c + 1] < key[c]) c++
                    if (key[c] >= k) break
                    key[i] = key[c]; v[i] = v[c]
                    i = c
                }
                key[i] = k; v[i] = x
            }
            return top
        }
    }

    // ---------------------------------------------------------------- route planner (grid distance field)

    private class Planner(val sim: Sim, val geo: Geo) {
        /** Cautious players keep the other balls out of the pull of a bigger hole, not just out of the hole. */
        var avoidRim = false
        var guard = 0f
        val cost = FloatArray(N)
        val heap = MinHeap(N * 4)

        private fun blockDisk(cx: Float, cy: Float, r: Float) {
            val i0 = max(0, floor(cx - r).toInt()); val i1 = min(GW - 1, ceil(cx + r).toInt())
            val j0 = max(0, floor(cy - r).toInt()); val j1 = min(GH - 1, ceil(cy + r).toInt())
            val r2 = r * r
            for (j in j0..j1) for (i in i0..i1) {
                val dx = i - cx; val dy = j - cy
                if (dx * dx + dy * dy < r2) cost[j * GW + i] = INF
            }
        }

        private fun softDisk(cx: Float, cy: Float, hard: Float, soft: Float, k: Float) {
            val i0 = max(0, floor(cx - soft).toInt()); val i1 = min(GW - 1, ceil(cx + soft).toInt())
            val j0 = max(0, floor(cy - soft).toInt()); val j1 = min(GH - 1, ceil(cy + soft).toInt())
            for (j in j0..j1) for (i in i0..i1) {
                val d = hypot(i - cx, j - cy)
                val idx = j * GW + i
                if (d < soft && cost[idx] < INF) cost[idx] += k * (soft - d) / (soft - hard)
            }
        }

        private fun blockCapsule(x1: Float, y1: Float, x2: Float, y2: Float, r: Float) {
            val i0 = max(0, floor(min(x1, x2) - r).toInt()); val i1 = min(GW - 1, ceil(max(x1, x2) + r).toInt())
            val j0 = max(0, floor(min(y1, y2) - r).toInt()); val j1 = min(GH - 1, ceil(max(y1, y2) + r).toInt())
            for (j in j0..j1) for (i in i0..i1) {
                if (segDist(i.toFloat(), j.toFloat(), x1, y1, x2, y2) < r) cost[j * GW + i] = INF
            }
        }

        private fun seedCapsule(out: FloatArray, x1: Float, y1: Float, x2: Float, y2: Float, r: Float) {
            val i0 = max(0, floor(min(x1, x2) - r).toInt()); val i1 = min(GW - 1, ceil(max(x1, x2) + r).toInt())
            val j0 = max(0, floor(min(y1, y2) - r).toInt()); val j1 = min(GH - 1, ceil(max(y1, y2) + r).toInt())
            for (j in j0..j1) for (i in i0..i1) {
                val idx = j * GW + i
                if (cost[idx] < INF && segDist(i.toFloat(), j.toFloat(), x1, y1, x2, y2) <= r) {
                    out[idx] = 0f
                    heap.push(0f, idx)
                }
            }
        }

        /**
         * Distance-to-goal for ball [bi]. [hi] >= 0: that hole; -1: any free hole of the ball's size;
         * -2: the point ([px], [py]); -3: anywhere the ball is lost (used to give it up on purpose).
         */
        fun compute(out: FloatArray, bi: Int, hi: Int, px: Float, py: Float, rigid: Boolean, sweepBlock: Boolean, margin: Float) {
            val b = sim.balls[bi]
            val so = b.size.ordinal
            val mask = geo.wallMask[so]
            for (i in 0 until N) cost[i] = if (mask[i]) INF else 1f
            val w = sim.w
            val eh = max(2f, guard)
            val es = max(9f, eh + 3f)
            val og = max(1f, guard)
            // open edges
            for (j in 0 until GH) for (i in 0 until GW) {
                val idx = j * GW + i
                if (cost[idx] >= INF) continue
                var c = 0f
                if (!geo.railL) { if (i < eh) { cost[idx] = INF; continue }; if (i < es) c += 2f * (es - i) / (es - eh) }
                if (!geo.railR) { val e = w.w - i; if (e < eh) { cost[idx] = INF; continue }; if (e < es) c += 2f * (es - e) / (es - eh) }
                if (!geo.railT) { if (j < eh) { cost[idx] = INF; continue }; if (j < es) c += 2f * (es - j) / (es - eh) }
                if (!geo.railB) { val e = w.h - j; if (e < eh) { cost[idx] = INF; continue }; if (e < es) c += 2f * (es - e) / (es - eh) }
                cost[idx] += c
            }
            // holes that would swallow this ball
            if (hi != -3) for (h in sim.holes) {
                if (h.filled || h.size.ordinal <= so) continue
                val hard = h.r - b.r * 0.45f + max(margin, guard)
                val soft = max(h.r + b.r * 0.3f + 2.5f, hard + 1f)
                if (h.moving && sweepBlock) {
                    blockCapsule(h.baseX - h.ampX, h.baseY - h.ampY, h.baseX + h.ampX, h.baseY + h.ampY, hard)
                } else {
                    softDisk(h.x, h.y, hard, soft, 3f)
                    blockDisk(h.x, h.y, if (h.moving) hard + 2.5f else hard)
                }
            }
            // places where another ball would be lost if every ball moved together
            if (rigid) for (o in sim.balls) {
                if (o === b || o.state != BallState.ROLLING) continue
                val dx = o.x - b.x
                val dy = o.y - b.y
                for (h in sim.holes) {
                    if (h.filled || h.size.ordinal <= o.size.ordinal) continue
                    blockDisk(h.x - dx, h.y - dy, if (avoidRim) h.r + o.r * 0.3f + 1.5f else h.r - o.r * 0.45f + og)
                }
                if (!geo.railL) { val lim = min(GW - 1, floor(og - dx).toInt()); for (j in 0 until GH) for (i in 0..lim) cost[j * GW + i] = INF }
                if (!geo.railR) { val lim = max(0, ceil(w.w - og - dx).toInt()); for (j in 0 until GH) for (i in lim until GW) cost[j * GW + i] = INF }
                if (!geo.railT) { val lim = min(GH - 1, floor(og - dy).toInt()); for (j in 0..lim) for (i in 0 until GW) cost[j * GW + i] = INF }
                if (!geo.railB) { val lim = max(0, ceil(w.h - og - dy).toInt()); for (j in lim until GH) for (i in 0 until GW) cost[j * GW + i] = INF }
            }

            for (i in 0 until N) out[i] = INF
            heap.n = 0
            when {
                hi == -2 -> seedCapsule(out, px, py, px, py, 1.6f)
                hi == -3 -> {
                    for (j in 0 until GH) for (i in 0 until GW) {
                        val idx = j * GW + i
                        val off = (!geo.railL && i < eh) || (!geo.railR && w.w - i < eh) || (!geo.railT && j < eh) || (!geo.railB && w.h - j < eh)
                        if (off && !mask[idx]) { cost[idx] = 1f; out[idx] = 0f; heap.push(0f, idx) }
                    }
                    for (h in sim.holes) {
                        if (h.filled || h.size.ordinal <= so) continue
                        seedCapsule(out, h.x, h.y, h.x, h.y, 2f)
                    }
                }
                else -> for ((k, h) in sim.holes.withIndex()) {
                    if (h.filled || h.size != b.size) continue
                    if (hi >= 0 && k != hi) continue
                    // a ball that stops inside the rim of its own hole is pulled in, so the rim is the goal
                    val r = h.r + b.r * 0.3f - 1.2f
                    if (h.moving) seedCapsule(out, h.baseX - h.ampX, h.baseY - h.ampY, h.baseX + h.ampX, h.baseY + h.ampY, r)
                    else seedCapsule(out, h.x, h.y, h.x, h.y, r)
                }
            }
            while (heap.n > 0) {
                val idx = heap.pop()
                val d = heap.lastKey
                if (d > out[idx]) continue
                val i = idx % GW
                val j = idx / GW
                val c0 = cost[idx]
                for (q in 0 until 8) {
                    val ni = i + DI[q]
                    val nj = j + DJ[q]
                    if (ni < 0 || nj < 0 || ni >= GW || nj >= GH) continue
                    val nidx = nj * GW + ni
                    val c1 = cost[nidx]
                    if (c1 >= INF) continue
                    val nd = d + DL[q] * 0.5f * (c0 + c1)
                    if (nd < out[nidx]) {
                        out[nidx] = nd
                        heap.push(nd, nidx)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- the bot

    private data class Params(
        val seed: Int,
        val randomOrder: Boolean,
        val rigid: Boolean,
        val sweepBlock: Boolean,
        val margin: Float,
        val wDanger: Float,
        val interval: Int,
        val h1: Int,
        val h2: Int,
        val allowSacrifice: Boolean,
        val backtrack: Boolean,
        val wSpeed: Float = 0.15f,
        val speedFree: Float = 0f,
        val avoidRim: Boolean = false,
        /** Scale for the steering tilts; braking stays full strength. */
        val tiltScale: Float = 1f,
        /** Keep every ball at least this far from open edges and from the catch radius of bigger holes. */
        val guard: Float = 0f,
    )

    private class Outcome(
        val won: Boolean, val steps: Int, val outs: Int, val homes: Int, val acts: FloatArray, val note: String,
        val outNotes: List<String>, val outEvents: List<Pair<Int, Int>>, val homeNotes: List<String>,
    )

    private class Bot(val sim: Sim, val geo: Geo, val p: Params) {
        val rng = Random(p.seed)
        val planner = Planner(sim, geo).also { it.avoidRim = p.avoidRim; it.guard = p.guard }
        val field = FloatArray(N)
        val probe = FloatArray(N)
        val base = Snap(sim.nb, sim.nh)
        val node = Snap(sim.nb, sim.nh)
        val bt = FloatArray(2)
        val offX = FloatArray(sim.nb)
        val offY = FloatArray(sim.nb)
        var sacrificeBall: Ball? = null
        var rolloutViolation = 0f
        val anyMoving = sim.holes.any { it.moving }

        fun tiltX(c: Int) = CX[c] * p.tiltScale
        fun tiltY(c: Int) = CY[c] * p.tiltScale

        fun brake() {
            var sx = 0f
            var sy = 0f
            var n = 0
            for (b in sim.balls) if (b.state == BallState.ROLLING) { sx += b.vx; sy += b.vy; n++ }
            if (n == 0) { bt[0] = 0f; bt[1] = 0f; return }
            bt[0] = (-sx / n * KB).coerceIn(-0.5f, 0.5f)
            bt[1] = (-sy / n * KB).coerceIn(-0.5f, 0.5f)
        }

        fun danger(): Float {
            var pen = 0f
            val w = sim.w
            for (b in sim.balls) {
                if (b.state != BallState.ROLLING && b.state != BallState.SPAWNING) continue
                if (b === sacrificeBall) continue
                for (h in sim.holes) {
                    if (h.filled || h.size.ordinal <= b.size.ordinal) continue
                    val lim = h.r + b.r * 0.3f + 3f
                    val d = hypot(h.x - b.x, h.y - b.y)
                    if (d < lim) pen += lim - d
                }
                val em = 7f
                if (!geo.railL && b.x < em) pen += em - b.x
                if (!geo.railR && w.w - b.x < em) pen += em - (w.w - b.x)
                if (!geo.railT && b.y < em) pen += em - b.y
                if (!geo.railB && w.h - b.y < em) pen += em - (w.h - b.y)
            }
            return pen
        }

        fun guardViolation(): Float {
            var v = 0f
            val w = sim.w
            val gd = p.guard
            for (b in sim.balls) {
                if (b.state != BallState.ROLLING) continue
                if (!geo.railL && b.x < gd) v += gd - b.x
                if (!geo.railR && w.w - b.x < gd) v += gd - (w.w - b.x)
                if (!geo.railT && b.y < gd) v += gd - b.y
                if (!geo.railB && w.h - b.y < gd) v += gd - (w.h - b.y)
                for (h in sim.holes) {
                    if (h.filled || h.size.ordinal <= b.size.ordinal) continue
                    val m = hypot(h.x - b.x, h.y - b.y) - (h.r - b.r * 0.45f)
                    if (m < gd) v += gd - m
                }
            }
            return v
        }

        fun score(b: Ball, baseOuts: Int, baseHomes: Int, f: FloatArray, gx: Float, gy: Float): Float {
            if (sim.g.won) return 1e9f
            var s = 0f
            var newOuts = sim.g.outs - baseOuts
            val sb = sacrificeBall
            if (sb != null && newOuts > 0 && !sim.g.lost && (sb.state == BallState.FALLING || sb.state == BallState.GONE || (sb.state == BallState.SINKING && !sb.sunkOk))) {
                newOuts--
                s += 5e4f
            }
            if (sim.g.lost) s -= 1e9f
            s -= newOuts * 1e6f
            s += (sim.homes() - baseHomes) * 2e4f
            if (b.state == BallState.ROLLING || b.state == BallState.SPAWNING) {
                var fv = fieldAt(f, b.x, b.y)
                if (fv >= 1e8f) fv = 400f + hypot(gx - b.x, gy - b.y)
                s -= fv
            }
            s -= p.wDanger * danger()
            s -= 1e5f * rolloutViolation
            s -= p.wSpeed * max(0f, sim.maxSpeed() - p.speedFree)
            return s
        }

        fun decide(b: Ball, prev: Int, f: FloatArray, gx: Float, gy: Float): Int {
            sim.logging = false
            sim.save(base)
            val baseOuts = base.outs
            val baseHomes = sim.homes()
            var bestS = -Float.MAX_VALUE
            var bestI = 1
            val guarded = p.guard > 0f
            for (c in 0 until NC) {
                if (c > 0) sim.load(base)
                rolloutViolation = 0f
                var k = 0
                while (k < p.h1 && !sim.g.over) {
                    if (c == 0) { brake(); sim.step(bt[0], bt[1]) } else sim.step(tiltX(c), tiltY(c))
                    k++
                    if (guarded) rolloutViolation = max(rolloutViolation, guardViolation())
                }
                k = 0
                while (k < p.h2 && !sim.g.over) {
                    brake()
                    sim.step(bt[0], bt[1])
                    k++
                    if (guarded) rolloutViolation = max(rolloutViolation, guardViolation())
                    if (k >= 6 && sim.maxSpeed() < 3f) break
                }
                var s = score(b, baseOuts, baseHomes, f, gx, gy)
                if (c == prev) s += 0.05f
                if (s > bestS) { bestS = s; bestI = c }
            }
            sim.load(base)
            sim.logging = true
            return bestI
        }

        fun offsetsDrifted(b: Ball): Boolean {
            for ((i, o) in sim.balls.withIndex()) {
                if (o === b || o.state != BallState.ROLLING) continue
                if (hypot(o.x - b.x - offX[i], o.y - b.y - offY[i]) > 1.5f) return true
            }
            return false
        }

        /** Steer ball [bi] to its goal. Returns one of the R_* codes. */
        fun runGoal(bi: Int, hi: Int, px: Float, py: Float, rigid: Boolean, maxSteps: Int, stuckSteps: Int): Int {
            val b = sim.balls[bi]
            val startHomes = sim.homes()
            val startOuts = sim.g.outs
            var lastField = -100000
            var best = INF
            var bestStep = sim.steps
            val end = sim.steps + maxSteps
            var last = -1
            var gx = px
            var gy = py
            var goalMoving = false
            if (hi >= -1) {
                var bd = INF
                for ((k, h) in sim.holes.withIndex()) {
                    if (h.filled || h.size != b.size || (hi >= 0 && k != hi)) continue
                    val d = hypot(h.baseX - b.x, h.baseY - b.y)
                    if (d < bd) { bd = d; gx = h.baseX; gy = h.baseY }
                    if (h.moving) goalMoving = true
                }
            } else if (hi == -3) {
                gx = b.x; gy = b.y
            }
            while (!sim.g.over && sim.steps < end) {
                if (sim.homes() > startHomes) return R_HOME
                if (sim.g.outs > startOuts) return R_OUT
                if (b.state != BallState.ROLLING) return R_OTHER
                val age = sim.steps - lastField
                if (age > 100000 || (age >= 24 && ((rigid && offsetsDrifted(b)) || (anyMoving && age >= 30)))) {
                    planner.compute(field, bi, hi, px, py, rigid, p.sweepBlock, p.margin)
                    lastField = sim.steps
                    for ((i, o) in sim.balls.withIndex()) { offX[i] = o.x - b.x; offY[i] = o.y - b.y }
                }
                val f = fieldAt(field, b.x, b.y)
                if (f < best - 1f) { best = f; bestStep = sim.steps }
                if (hi == -2 && f < 2.5f) return R_REACHED
                val limit = if (goalMoving && f < 4f) max(stuckSteps, 960) else stuckSteps
                if (sim.steps - bestStep > limit) return R_STUCK
                last = decide(b, last, field, gx, gy)
                var k = 0
                while (k < p.interval && !sim.g.over) {
                    if (last == 0) { brake(); sim.step(bt[0], bt[1]) } else sim.step(tiltX(last), tiltY(last))
                    k++
                }
            }
            return R_TIMEOUT
        }

        fun idle(steps: Int) {
            var k = 0
            while (k < steps && !sim.g.over) { brake(); sim.step(bt[0], bt[1]); k++ }
        }

        fun reach(bi: Int, rigid: Boolean): Float {
            planner.compute(probe, bi, -1, 0f, 0f, rigid, p.sweepBlock, p.margin)
            val b = sim.balls[bi]
            return fieldAt(probe, b.x, b.y)
        }

        fun rollingBalls(): List<Int> = (0 until sim.nb).filter { sim.balls[it].state == BallState.ROLLING }

        /** A place to push a ball to in the hope of changing the formation: sand, beside a wall, or anywhere. */
        fun shakeTo(bi: Int) {
            val b = sim.balls[bi]
            var gx: Float
            var gy: Float
            val r = rng.nextFloat()
            val sand = sim.w.sand
            if (sand.isNotEmpty() && r < 0.35f) {
                val z = sand[rng.nextInt(sand.size)]
                gx = z.x + 2f + rng.nextFloat() * (z.w - 4f)
                gy = z.y + 2f + rng.nextFloat() * (z.h - 4f)
            } else if (geo.inner.isNotEmpty() && r < 0.7f) {
                val wl = geo.inner[rng.nextInt(geo.inner.size)]
                val t = rng.nextFloat()
                val ex = wl[2] - wl[0]
                val ey = wl[3] - wl[1]
                val len = max(1e-3f, hypot(ex, ey))
                val side = if (rng.nextBoolean()) 1f else -1f
                gx = wl[0] + ex * t - ey / len * side * (b.r + 2f)
                gy = wl[1] + ey * t + ex / len * side * (b.r + 2f)
            } else {
                gx = 8f + rng.nextFloat() * 84f
                gy = 8f + rng.nextFloat() * 134f
            }
            runGoal(bi, -2, gx.coerceIn(6f, 94f), gy.coerceIn(6f, 144f), false, 4 * 120, 150)
        }

        /** From a stuck position, search (with take-backs) for a move after which some ball can reach its hole. */
        fun unlock(): Boolean {
            sim.save(node)
            val homes0 = sim.homes()
            var tries = 0
            while (tries < 20 && !sim.g.over) {
                tries++
                val rolling = rollingBalls()
                if (rolling.isEmpty()) return false
                val outs0 = sim.g.outs
                shakeTo(rolling[rng.nextInt(rolling.size)])
                if (!sim.g.over && sim.g.outs == outs0) {
                    if (sim.homes() > homes0) return true
                    if (rollingBalls().any { reach(it, true) < 1e8f }) return true
                    if (rng.nextFloat() < 0.25f) { sim.save(node); continue }
                }
                sim.load(node)
            }
            sim.load(node)
            return false
        }

        fun play(maxSteps: Int): Outcome {
            val failed = BooleanArray(sim.nb)
            var shakes = 0
            var note = ""
            while (!sim.g.over && sim.steps < maxSteps) {
                val rolling = rollingBalls()
                if (rolling.isEmpty()) { idle(6); continue }
                val d = FloatArray(sim.nb) { INF }
                for (i in rolling) if (!failed[i]) d[i] = reach(i, p.rigid)
                val reachable = rolling.filter { !failed[it] && d[it] < 1e8f }
                if (reachable.isNotEmpty()) {
                    val pick = if (p.randomOrder && rng.nextFloat() < 0.6f) reachable[rng.nextInt(reachable.size)]
                    else reachable.minByOrNull { -sim.balls[it].size.ordinal * 1000f + d[it] }!!
                    val hole = if (p.randomOrder && rng.nextFloat() < 0.3f) {
                        val own = sim.holes.indices.filter { !sim.holes[it].filled && sim.holes[it].size == sim.balls[pick].size }
                        own[rng.nextInt(own.size)]
                    } else -1
                    when (runGoal(pick, hole, 0f, 0f, p.rigid, 25 * 120, 300)) {
                        R_HOME -> for (i in failed.indices) failed[i] = false
                        R_STUCK, R_TIMEOUT -> failed[pick] = true
                        else -> {}
                    }
                    continue
                }
                // Nothing can make progress as things stand.
                shakes++
                if (shakes > 16) {
                    note = "gave up: no ball can reach its hole;" + rolling.joinToString("") { " ball $it at (%.1f, %.1f) v=(%.1f, %.1f)".format(sim.balls[it].x, sim.balls[it].y, sim.balls[it].vx, sim.balls[it].vy) }
                    break
                }
                for (i in failed.indices) failed[i] = false
                if (p.backtrack && unlock()) continue
                val bi = rolling[rng.nextInt(rolling.size)]
                val r = rng.nextFloat()
                if (p.allowSacrifice && sim.g.outs < 2 && r < 0.5f) {
                    // lose one ball on purpose: it comes back at its spawn point, which changes the formation
                    sacrificeBall = sim.balls[bi]
                    runGoal(bi, -3, 0f, 0f, false, 6 * 120, 2 * 120)
                    sacrificeBall = null
                } else if (r < 0.75f) {
                    // go for a hole anyway, planning for this ball alone (the look-ahead still refuses to lose the others)
                    runGoal(bi, -1, 0f, 0f, false, 12 * 120, 300)
                } else {
                    shakeTo(bi)
                }
            }
            if (!sim.g.over && note.isEmpty()) note = "ran out of time"
            if (sim.g.lost) note = "lost on outs"
            return Outcome(
                sim.g.won, sim.steps, sim.g.outs, sim.balls.count { it.state == BallState.HOME },
                sim.acts.copyOf(sim.steps * 2), note, sim.outNotes.map { it.third }, sim.outNotes.map { it.first to it.second }, sim.homeNotes.map { it.second },
            )
        }
    }

    // ---------------------------------------------------------------- simple checks (no bot)

    /** Phone left flat for 30 s: nothing should move, sink or fall. */
    private fun flatCheck(n: Int): String? {
        val g = SoloGame(n)
        val start = g.world.balls.map { it.x to it.y }
        repeat(30 * 120) { g.step(DT, 0f, 0f) }
        val moved = g.world.balls.withIndex().maxOf { (i, b) -> hypot(b.x - start[i].first, b.y - start[i].second) }
        val homes = g.world.balls.count { it.state == BallState.HOME }
        return if (g.outs > 0 || homes > 0 || moved > 0.5f) "plays itself when flat: outs=${g.outs} homes=$homes moved=%.1f".format(moved) else null
    }

    /** Is the level won by holding one fixed tilt? Returns a description of the best such tilt. */
    private fun constantTiltWin(n: Int): String? {
        var best: String? = null
        var bestOuts = 99
        for (k in 0 until 16) for (m in listOf(0.1f, 0.25f, 0.5f)) {
            val a = k * Math.PI / 8.0
            val tx = (m * Math.cos(a)).toFloat()
            val ty = (m * Math.sin(a)).toFloat()
            val g = SoloGame(n)
            var steps = 0
            while (!g.over && steps < 25 * 120) { g.step(DT, tx, ty); steps++ }
            if (g.won && g.outs < bestOuts) {
                bestOuts = g.outs
                best = "won by holding one fixed tilt (%.2f, %.2f): %.1fs, %d outs".format(tx, ty, steps * DT, g.outs)
            }
        }
        return best
    }

    /** The crude player from CoreTest: tilt straight at the nearest right-sized hole of the first loose ball. */
    private fun naive(n: Int): Triple<Boolean, Int, Float> {
        val g = SoloGame(n)
        var steps = 0
        while (!g.over && steps < 90 * 120) {
            val b = g.world.balls.firstOrNull { it.state == BallState.ROLLING }
            var tx = 0f
            var ty = 0f
            if (b != null) {
                val h = g.world.holes.filter { !it.filled && it.size == b.size }.minByOrNull { hypot(it.x - b.x, it.y - b.y) }
                if (h != null) {
                    val dx = h.x - b.x
                    val dy = h.y - b.y
                    val d = max(1e-3f, hypot(dx, dy))
                    tx = (dx / d * 0.12f - b.vx * 0.004f).coerceIn(-0.5f, 0.5f)
                    ty = (dy / d * 0.12f - b.vy * 0.004f).coerceIn(-0.5f, 0.5f)
                }
            }
            g.step(DT, tx, ty)
            steps++
        }
        return Triple(g.won, g.outs, steps * DT)
    }

    private fun segSegDist(a: FloatArray, b: FloatArray): Float {
        fun cross(ox: Float, oy: Float, px: Float, py: Float, qx: Float, qy: Float) = (px - ox) * (qy - oy) - (py - oy) * (qx - ox)
        val d1 = cross(a[0], a[1], a[2], a[3], b[0], b[1])
        val d2 = cross(a[0], a[1], a[2], a[3], b[2], b[3])
        val d3 = cross(b[0], b[1], b[2], b[3], a[0], a[1])
        val d4 = cross(b[0], b[1], b[2], b[3], a[2], a[3])
        if (d1 * d2 < 0f && d3 * d4 < 0f) return 0f
        return min(
            min(segDist(a[0], a[1], b[0], b[1], b[2], b[3]), segDist(a[2], a[3], b[0], b[1], b[2], b[3])),
            min(segDist(b[0], b[1], a[0], a[1], a[2], a[3]), segDist(b[2], b[3], a[0], a[1], a[2], a[3])),
        )
    }

    private fun sweep(h: Hole) = floatArrayOf(h.baseX - h.ampX, h.baseY - h.ampY, h.baseX + h.ampX, h.baseY + h.ampY)

    private class StaticInfo {
        val notes = ArrayList<String>()
        var wallsOdd = false
        var dropped = false
        var pairs = 0
        var feasible = 0
        var bestSlack = Float.NEGATIVE_INFINITY
        var slideX = 0f
        var slideY = 0f
        var closestSweep = 99f
        var trapText = ""
        var needsTiming = false
        var unreachable = false
        val edgeSpawns = ArrayList<String>()
    }

    /** Geometry-only observations about a level. */
    private fun staticInfo(n: Int): StaticInfo {
        val w = Levels.build(n).world
        val geo = Geo(w)
        val info = StaticInfo()
        if (n >= 5) {
            val d = ((n - 5) / 35f).coerceIn(0f, 1f)
            val nBalls = (2 + (d * 4.2f).toInt()).coerceAtMost(6)
            val nHoles = (nBalls + 1 + (d * 3f).toInt()).coerceAtMost(9)
            val nMoving = if (n < 14) 0 else 1 + (n - 14) / 9
            if (w.balls.size != nBalls || w.holes.size != nHoles || w.holes.count { it.moving } != nMoving) {
                info.dropped = true
                info.notes.add("generator dropped pieces: wanted $nBalls balls/$nHoles holes/$nMoving moving, got ${w.balls.size}/${w.holes.size}/${w.holes.count { it.moving }}")
            }
        }
        for (i in geo.inner.indices) for (j in i + 1 until geo.inner.size) {
            val d = segSegDist(geo.inner[i], geo.inner[j])
            if (d < 2f * (Size.S.ballR + World.WALL_T)) {
                info.wallsOdd = true
                info.notes.add((if (d == 0f) "walls $i and $j cross" else "walls $i and $j are %.1f apart (no ball fits between)".format(d)))
            }
        }
        for ((hi, h) in w.holes.withIndex()) {
            if (!h.moving) continue
            for ((wi, wl) in geo.inner.withIndex()) {
                val d = segSegDist(sweep(h), wl) - h.r
                if (d < 1f) info.notes.add("moving hole $hi slides under wall $wi")
            }
            for (b in w.balls) {
                if (h.size.ordinal < b.size.ordinal) continue
                val s = sweep(h)
                val d = segDist(b.x, b.y, s[0], s[1], s[2], s[3]) - (h.r + b.r * 0.3f)
                info.closestSweep = min(info.closestSweep, d)
                if (d < 2f) info.notes.add("moving hole $hi passes %.1f from pulling ball ${b.id} at its spawn".format(d))
            }
        }
        // How far can the whole formation slide before some ball leaves through an open edge, and
        // which balls could be brought to (the rim of) a hole of their size by sliding alone?
        if (w.balls.size >= 2) {
            val big = 1e6f
            info.slideX = (if (geo.railL) big else w.balls.minOf { it.x }) + (if (geo.railR) big else w.w - w.balls.maxOf { it.x })
            info.slideY = (if (geo.railT) big else w.balls.minOf { it.y }) + (if (geo.railB) big else w.h - w.balls.maxOf { it.y })
            for (b in w.balls) {
                val others = w.balls.filter { it !== b }
                val loX = if (geo.railL) -big else -others.minOf { it.x }
                val hiX = if (geo.railR) big else w.w - others.maxOf { it.x }
                val loY = if (geo.railT) -big else -others.minOf { it.y }
                val hiY = if (geo.railB) big else w.h - others.maxOf { it.y }
                for ((hi, h) in w.holes.withIndex()) {
                    if (h.size != b.size) continue
                    info.pairs++
                    val reachR = h.r + b.r * 0.3f - 0.5f
                    var slack = Float.NEGATIVE_INFINITY
                    for (q in 0..20) {
                        val t = if (h.moving) -1f + q * 0.1f else 0f
                        for (ring in 0..2) for (a in 0 until 16) {
                            val rr = reachR * ring / 2f
                            val dx = h.baseX + h.ampX * t + rr * Math.cos(a * Math.PI / 8).toFloat() - b.x
                            val dy = h.baseY + h.ampY * t + rr * Math.sin(a * Math.PI / 8).toFloat() - b.y
                            slack = max(slack, min(min(dx - loX, hiX - dx), min(dy - loY, hiY - dy)))
                        }
                    }
                    if (slack > 0f) info.feasible++
                    if (slack > info.bestSlack) info.bestSlack = slack
                    info.notes.add("slide ball ${b.id} (${b.size}) to hole $hi: " + (if (slack > 0f) "possible, leaves another ball %.1f from an open edge at best".format(min(slack, 999f)) else "impossible, another ball would be %.1f past an open edge".format(-slack)))
                }
            }
        }
        for (b in w.balls) {
            var e = 999f
            if (!geo.railL) e = min(e, b.x)
            if (!geo.railR) e = min(e, w.w - b.x)
            if (!geo.railT) e = min(e, b.y)
            if (!geo.railB) e = min(e, w.h - b.y)
            if (e < 14f) info.edgeSpawns.add("L$n.b${b.id}(%.1f)".format(e))
        }
        // A ball alone on the tray, rolling from its spawn under a steady 0.3 tilt: how soon can it be lost?
        for (bi in w.balls.indices) {
            var deadly = 0
            var soonest = 99f
            for (k in 0 until 16) {
                val w2 = Levels.build(n).world
                val keep = w2.balls[bi]
                w2.balls.removeAll { it !== keep }
                val a = k * Math.PI / 8.0
                var t = 0
                var lost = false
                while (t < 96 && !lost) {
                    w2.step(DT, (0.3 * Math.cos(a)).toFloat(), (0.3 * Math.sin(a)).toFloat())
                    t++
                    if (keep.state == BallState.FALLING || (keep.state == BallState.SINKING && !keep.sunkOk)) lost = true
                    if (keep.state != BallState.ROLLING) break
                }
                if (lost) { deadly++; soonest = min(soonest, t * DT) }
            }
            val b = w.balls[bi]
            val text = "ball ${b.id} (${b.size}) spawn (%.0f, %.0f): alone, a steady 0.3 tilt loses it within 0.8s in $deadly of 16 directions (soonest %.2fs)".format(b.x, b.y, soonest)
            info.notes.add(text)
            if (deadly >= 8 || soonest < 0.5f) info.trapText = text
        }
        // Can each ball, alone, reach a hole of its size at all, and without timing a moving bigger hole?
        val sim = Sim(n, null, 0L)
        val planner = Planner(sim, geo)
        val f = FloatArray(N)
        for ((bi, b) in sim.balls.withIndex()) {
            planner.compute(f, bi, -1, 0f, 0f, false, true, 2f)
            if (fieldAt(f, b.x, b.y) >= 1e8f) {
                planner.compute(f, bi, -1, 0f, 0f, false, false, 1f)
                if (fieldAt(f, b.x, b.y) >= 1e8f) {
                    info.unreachable = true
                    info.notes.add("ball ${b.id}: no route to any hole of its size even alone")
                } else {
                    info.needsTiming = true
                    info.notes.add("ball ${b.id}: every route to a hole of its size crosses the path of a moving bigger hole")
                }
            }
        }
        return info
    }

    private fun describe(n: Int): String {
        val w = Levels.build(n).world
        val geo = Geo(w)
        val sb = StringBuilder()
        sb.append("LEVEL $n  rails(T,R,B,L)=${geo.railsText()}  balls=${w.balls.size} holes=${w.holes.size} innerWalls=${geo.inner.size} sand=${w.sand.size} moving=${w.holes.count { it.moving }}\n")
        for (b in w.balls) sb.append("  ball ${b.id} ${b.size} at (%.1f, %.1f)\n".format(b.x, b.y))
        for ((i, h) in w.holes.withIndex()) {
            sb.append("  hole $i ${h.size} at (%.1f, %.1f)".format(h.baseX, h.baseY))
            if (h.moving) sb.append("  moves x+-%.1f y+-%.1f period %.2fs phase %.2f".format(h.ampX, h.ampY, h.period, h.phase))
            sb.append("\n")
        }
        for ((i, wl) in geo.inner.withIndex()) sb.append("  wall $i (%.1f, %.1f)-(%.1f, %.1f)\n".format(wl[0], wl[1], wl[2], wl[3]))
        for (z in w.sand) sb.append("  sand x %.1f..%.1f y %.1f..%.1f\n".format(z.x, z.x + z.w, z.y, z.y + z.h))
        return sb.toString()
    }

    // ---------------------------------------------------------------- per-level driver

    private class LevelResult(val level: Int) {
        var won = false
        var verified = false
        var attempts = 0
        var wins = 0
        var cleanWins = 0
        var totalAttempts = 0
        var firstWinAttempt = 0
        var minOuts = -1
        var bestSec = 0f
        var edgeMargin = 999f
        var holeMargin = 999f
        var room = -1f
        var roomSec = 0f
        var roomNotes: List<String> = emptyList()
        var repeats = IntArray(0)
        var bestHomes = 0
        val handicapWins = IntArray(HANDICAPS.size)
        val handicapOuts = FloatArray(HANDICAPS.size)
        val handicapSec = FloatArray(HANDICAPS.size)
        var handicapOutEvents = 0
        var quickRepeatOuts = 0
        var naiveText = ""
        var naiveWon = false
        var naiveOuts = 0
        val flags = ArrayList<String>()
        var info = StaticInfo()
        var shape = ""
        var rails = 0
        var cpuSec = 0f
        var balls = 0
    }

    private fun idealParams(level: Int, k: Int): Params {
        val r = Random(level * 1000 + k)
        return if (k == 0) Params(1, false, true, true, 2f, 0.5f, 4, 8, 60, false, true)
        else Params(
            seed = level * 131 + k,
            randomOrder = k % 2 == 1,
            rigid = k % 5 != 4,
            sweepBlock = k % 3 != 2,
            margin = listOf(2f, 1.2f, 3f)[r.nextInt(3)],
            wDanger = listOf(0.5f, 0.2f, 1f)[r.nextInt(3)],
            interval = listOf(4, 3, 6)[r.nextInt(3)],
            h1 = listOf(8, 6, 12)[r.nextInt(3)],
            h2 = listOf(60, 80)[r.nextInt(2)],
            allowSacrifice = k >= IDEAL_ATTEMPTS && k % 2 == 1,
            backtrack = true,
            speedFree = if (k < IDEAL_ATTEMPTS) 0f else 3f,
        )
    }

    private fun verify(level: Int, acts: FloatArray): Boolean {
        val g = SoloGame(level)
        var i = 0
        while (i < acts.size && !g.over) {
            if (abs(acts[i]) > 0.5f || abs(acts[i + 1]) > 0.5f) return false
            g.step(DT, acts[i], acts[i + 1])
            i += 2
        }
        return g.won
    }

    /** Replays a run and reports how close any rolling ball came to an open edge and to being swallowed by a bigger hole. */
    private fun closestCalls(level: Int, acts: FloatArray, geo: Geo): Pair<Float, Float> {
        val g = SoloGame(level)
        var edge = 999f
        var hole = 999f
        var i = 0
        while (i < acts.size && !g.over) {
            g.step(DT, acts[i], acts[i + 1])
            i += 2
            for (b in g.world.balls) {
                if (b.state != BallState.ROLLING) continue
                if (!geo.railL) edge = min(edge, b.x)
                if (!geo.railR) edge = min(edge, g.world.w - b.x)
                if (!geo.railT) edge = min(edge, b.y)
                if (!geo.railB) edge = min(edge, g.world.h - b.y)
                for (h in g.world.holes) {
                    if (h.filled || h.size.ordinal <= b.size.ordinal) continue
                    hole = min(hole, hypot(h.x - b.x, h.y - b.y) - (h.r - b.r * 0.45f))
                }
            }
        }
        return edge to hole
    }

    private fun solveLevel(n: Int, log: StringBuilder): LevelResult {
        val t0 = System.currentTimeMillis()
        val w = Levels.build(n).world
        val geo = Geo(w)
        val res = LevelResult(n)
        res.balls = w.balls.size
        res.repeats = IntArray(w.balls.size)
        res.rails = geo.rails
        res.shape = "b%d h%d r%s w%d s%d m%d".format(w.balls.size, w.holes.size, geo.railsText(), geo.inner.size, w.sand.size, w.holes.count { it.moving })

        // ideal hand: a fixed number of attempts for statistics, then more (some allowed to give a ball up) until a clean win
        var best: Outcome? = null
        var k = 0
        while (k < IDEAL_ATTEMPTS || (k < IDEAL_MAX && (best == null || best.outs > 0))) {
            val sim = Sim(n, null, 0L)
            val out = Bot(sim, geo, idealParams(n, k)).play(150 * 120)
            k++
            res.bestHomes = max(res.bestHomes, out.homes)
            if (k <= IDEAL_ATTEMPTS) {
                res.attempts++
                if (out.won) res.wins++
                if (out.won && out.outs == 0) res.cleanWins++
            }
            if (out.won && res.firstWinAttempt == 0) res.firstWinAttempt = k
            if (out.won && (best == null || out.outs < best.outs || (out.outs == best.outs && out.steps < best.steps))) best = out
            log.append("  L$n ideal attempt $k: won=${out.won} homes=${out.homes}/${sim.nb} outs=${out.outs} sim=%.1fs %s\n".format(out.steps * DT, out.note))
            for (s in out.outNotes) log.append("      out: $s\n")
        }
        res.totalAttempts = k
        if (best != null) {
            res.won = true
            res.minOuts = best.outs
            res.bestSec = best.steps * DT
            res.verified = verify(n, best.acts)
            val cc = closestCalls(n, best.acts, geo)
            res.edgeMargin = cc.first
            res.holeMargin = cc.second
            if (best.outs == 0) res.room = min(cc.first, cc.second)
            log.append("  L$n best win (%d outs, %.1fs): closest to an open edge %.1f, closest to being swallowed %.1f\n".format(best.outs, best.steps * DT, cc.first, cc.second))
            for (s in best.homeNotes) log.append("      $s\n")
            for (s in best.outNotes) log.append("      out: $s\n")
        }

        // How much room does a clean win leave? Re-run the ideal hand with a keep-clear distance, widest first.
        guards@ for (gd in GUARDS) {
            if (res.room >= gd - 0.5f) break
            for (j in 0 until GUARD_TRIES) {
                val sim = Sim(n, null, 0L)
                val out = Bot(sim, geo, idealParams(n, j).copy(guard = gd, speedFree = 3f)).play(90 * 120)
                log.append("  L$n keep-clear %.0f try ${j + 1}: won=${out.won} outs=${out.outs} sim=%.1fs".format(gd, out.steps * DT))
                if (out.won && out.outs == 0 && verify(n, out.acts)) {
                    val cc = closestCalls(n, out.acts, geo)
                    val room = min(cc.first, cc.second)
                    log.append(" room %.1f\n".format(room))
                    if (room > res.room) {
                        res.room = room
                        res.roomSec = out.steps * DT
                        res.roomNotes = out.homeNotes
                    }
                    if (room >= gd - 0.5f) break@guards
                } else log.append("\n")
            }
        }
        log.append("  L$n roomiest clean win keeps %.1f clear (%.1fs)\n".format(res.room, res.roomSec))
        for (s in res.roomNotes) log.append("      $s\n")

        // clumsy hands
        for ((hi, hc) in HANDICAPS.withIndex()) {
            var outsSum = 0f
            val secs = ArrayList<Float>()
            for (t in 0 until HANDICAP_TRIALS) {
                val sim = Sim(n, hc, n * 7919L + hi * 104729L + t)
                val p = Params(n * 17 + t, false, true, true, 3f, 1f, 10, 12, 100, false, false, wSpeed = 0.4f, speedFree = 3f, avoidRim = true, tiltScale = 0.5f)
                val out = Bot(sim, geo, p).play(120 * 120)
                val ok = out.won && verify(n, out.acts)
                if (ok) { res.handicapWins[hi]++; outsSum += out.outs; secs.add(out.steps * DT) }
                res.handicapOutEvents += out.outEvents.size
                for (i in out.outEvents.indices) for (j in i + 1 until out.outEvents.size) {
                    if (out.outEvents[i].second == out.outEvents[j].second && out.outEvents[j].first - out.outEvents[i].first < 360) {
                        res.quickRepeatOuts++
                        res.repeats[out.outEvents[i].second]++
                    }
                }
                log.append("  L$n ${hc.name} trial ${t + 1}: won=$ok homes=${out.homes}/${sim.nb} outs=${out.outs} sim=%.1fs %s\n".format(out.steps * DT, out.note))
                for (s in out.outNotes) log.append("      out: $s\n")
            }
            if (secs.isNotEmpty()) {
                res.handicapOuts[hi] = outsSum / secs.size
                secs.sort()
                res.handicapSec[hi] = secs[secs.size / 2]
            }
        }

        val nv = naive(n)
        res.naiveWon = nv.first
        res.naiveOuts = nv.second
        res.naiveText = if (nv.first) "won(%d outs, %.0fs)".format(nv.second, nv.third) else if (nv.second >= 3) "lost(%.0fs)".format(nv.third) else "stuck(${nv.second} outs)"
        flatCheck(n)?.let { res.flags.add(it) }
        constantTiltWin(n)?.let { res.flags.add(it) }
        res.info = staticInfo(n)
        res.cpuSec = (System.currentTimeMillis() - t0) / 1000f
        return res
    }

    @Test fun probe() {
        // Slow (minutes), so it only runs when asked for: PROBE=1 or PROBE_LEVELS=22,38.
        org.junit.Assume.assumeTrue(System.getenv("PROBE") != null || System.getenv("PROBE_LEVELS") != null)
        val outDir = File("build/solvability").apply { mkdirs() }
        val geoText = StringBuilder()
        for (n in 1..Levels.COUNT) geoText.append(describe(n)).append('\n')
        File(outDir, "levels.txt").writeText(geoText.toString())

        val t0 = System.currentTimeMillis()
        val pool = Executors.newFixedThreadPool(THREADS)
        val logs = Array(Levels.COUNT + 1) { StringBuilder() }
        // latest levels first: they take longest, so they should not all queue up at the end
        val only = System.getenv("PROBE_LEVELS")?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
        val futures = (Levels.COUNT downTo 1).filter { only == null || it in only }.map { n -> pool.submit(Callable { solveLevel(n, logs[n]) }) }
        val results = futures.map { it.get() }.sortedBy { it.level }
        pool.shutdown()
        File(outDir, "attempts.txt").writeText(logs.joinToString("\n") { it.toString() })

        // identical layouts?
        val sig = (1..Levels.COUNT).associateWith { n ->
            val w = Levels.build(n).world
            w.balls.joinToString { "%s%.0f,%.0f".format(it.size, it.x, it.y) } + "|" + w.holes.joinToString { "%s%.0f,%.0f".format(it.size, it.baseX, it.baseY) }
        }
        val dupes = sig.entries.groupBy({ it.value }, { it.key }).values.filter { it.size > 1 }

        val sb = StringBuilder()
        val fl = StringBuilder()
        sb.append("==== Tilt & Sort solvability probe ====\n")
        sb.append("ideal: exact instant tilt, $IDEAL_ATTEMPTS parameter sets (more, up to $IDEAL_MAX, while no zero-out win). " +
            "Its best run is replayed on a fresh SoloGame. room = in the roomiest zero-out win found, the closest any ball came to an open edge or to a bigger hole's catch radius (tray is 100 x 150; '-' = nothing to avoid).\n")
        sb.append(HANDICAPS.joinToString(". ") { "${it.name}: same brain, half-strength steering, hand lag %.2fs, %.2fs delay it does not know about, wobble %.2fg".format(it.lagTau, it.delay * DT, it.sigma) } +
            "; $HANDICAP_TRIALS trials each, shown as wins (avg outs, median s of the wins). naive: aims straight at the hole.\n")
        sb.append("shape: b balls, h holes, r rails (T,R,B,L), w inner walls, s sand, m moving holes\n")
        for (r in results) {
            fun m(v: Float) = if (v > 99f) "  - " else "%4.1f".format(v)
            val head = if (r.won) "L%02d WON     ideal %d/%d wins (%d clean), fewest outs %d (%d tries) in %5.1fs %s, room %s".format(
                r.level, r.wins, r.attempts, r.cleanWins, r.minOuts, r.totalAttempts, r.bestSec, if (r.verified) "replay-ok" else "REPLAY-MISMATCH", if (r.room < 0f) " none" else m(r.room))
            else "L%02d NOT WON ideal 0/%d, best %d/%d balls home".format(r.level, r.totalAttempts, r.bestHomes, r.balls)
            sb.append(head)
            for ((hi, hc) in HANDICAPS.withIndex()) {
                sb.append(" | %s %d/%d".format(hc.name, r.handicapWins[hi], HANDICAP_TRIALS))
                if (r.handicapWins[hi] > 0) sb.append(" (%.1f, %.0fs)".format(r.handicapOuts[hi], r.handicapSec[hi]))
            }
            sb.append(" | naive ${r.naiveText} | ${r.shape}\n")
            for (f in r.flags) fl.append("L%02d: %s\n".format(r.level, f))
            for (f in r.info.notes) fl.append("L%02d: %s\n".format(r.level, f))
            for ((bi, c) in r.repeats.withIndex()) if (c > 0) fl.append("L%02d: ball %d was lost twice within 3s in %d of %d clumsy trials\n".format(r.level, bi, c, 2 * HANDICAP_TRIALS))
        }
        for (d in dupes) fl.append("levels $d have the same layout\n")

        fun lv(list: List<LevelResult>) = if (list.isEmpty()) "none" else list.joinToString(" ") { "L${it.level}" }
        sb.append("not won: ${lv(results.filter { !it.won })}; no zero-out win found: " +
            (results.filter { it.won && it.minOuts > 0 }.joinToString(" ") { "L${it.level}(${it.minOuts} outs)" }.ifEmpty { "none" }) +
            "; replay mismatches: ${lv(results.filter { it.won && !it.verified })}; identical layouts: ${dupes.size}\n")
        sb.append("plays itself when flat: ${lv(results.filter { r -> r.flags.any { it.startsWith("plays itself") } })}; won by one fixed tilt: ${lv(results.filter { r -> r.flags.any { it.startsWith("won by holding") } })}; " +
            "generator dropped a ball or hole: ${lv(results.filter { it.info.dropped })}; a ball with no route to its hole: ${lv(results.filter { it.info.unreachable })}; route only by timing a moving bigger hole: ${lv(results.filter { it.info.needsTiming })}\n")
        sb.append("walls doubled or crossing: ${lv(results.filter { it.info.wallsOdd })}; closest a moving hole's pull comes to a spawn: %.1f units; spawn where most steady tilts lose the ball at once: ".format(results.minOf { it.info.closestSweep }) +
            (results.filter { it.info.trapText.isNotEmpty() }.joinToString("; ") { "L${it.level} ${it.info.trapText}" }.ifEmpty { "none" }) + "\n")
        sb.append("first ball by sliding all balls together (no-rail levels; pairings possible / best gap left to an open edge): " +
            results.filter { it.rails == 0 }.joinToString(" ") { "L%d:%d/%d,%.0f".format(it.level, it.info.feasible, it.info.pairs, it.info.bestSlack) } + "\n")
        sb.append("naive bot wins with 0 outs: ${lv(results.filter { it.naiveWon && it.naiveOuts == 0 })}; in the clumsy trials ${results.sumOf { it.quickRepeatOuts }} of ${results.sumOf { it.handicapOutEvents }} outs were the same ball lost again within 3s; worst (level.ball x times): " +
            results.flatMap { r -> r.repeats.withIndex().filter { it.value >= 5 }.map { "L${r.level}.b${it.index} x${it.value}" } }.joinToString(" ").ifEmpty { "none" } + "\n")
        sb.append("spawn points within 14 units of an open edge (level.ball(distance)): " + results.flatMap { it.info.edgeSpawns }.joinToString(" ").ifEmpty { "none" } + "\n")
        sb.append("by band (levels: ideal clean wins per $IDEAL_ATTEMPTS tries / median fastest clean s / min room | careful win% | sloppy win% | naive wins):\n")
        for (band in listOf(1..4, 5..13, 14..21, 22..31, 32..40)) {
            val rs = results.filter { it.level in band }
            if (rs.isEmpty()) continue
            val secs = rs.filter { it.won }.map { it.bestSec }.sorted()
            sb.append("  L%02d-%02d: %.1f / %.1fs / %.1f | %.0f%% | %.0f%% | %d of %d\n".format(
                band.first, band.last, rs.map { it.cleanWins }.average(), if (secs.isEmpty()) 0f else secs[secs.size / 2],
                rs.minOf { if (it.room < 0f) 0f else it.room },
                rs.sumOf { it.handicapWins[0] } * 100.0 / (rs.size * HANDICAP_TRIALS), rs.sumOf { it.handicapWins[1] } * 100.0 / (rs.size * HANDICAP_TRIALS),
                rs.count { it.naiveWon }, rs.size))
        }
        sb.append("details: app/build/solvability/{levels,attempts,flags,results}.txt; total %.0fs\n".format((System.currentTimeMillis() - t0) / 1000f))
        File(outDir, "results.txt").writeText(sb.toString())
        File(outDir, "flags.txt").writeText(fl.toString())
        println(sb)
    }

    companion object {
        const val DT = 1f / 120f
        const val GW = 101
        const val GH = 151
        const val N = GW * GH
        const val INF = 1e9f
        const val KB = 0.064f
        const val DEAD_ZONE = 0.012f
        const val NOISE_TAU = 0.4f
        const val THREADS = 4
        const val IDEAL_ATTEMPTS = 8
        const val IDEAL_MAX = 30
        const val HANDICAP_TRIALS = 10
        const val GUARD_TRIES = 4
        val GUARDS = floatArrayOf(9f, 7f, 5f, 3f, 1.5f)

        private val HANDICAPS = listOf(
            Handicap("careful", 0.10f, 12, 0.02f),
            Handicap("sloppy", 0.15f, 24, 0.05f),
        )

        const val R_HOME = 0
        const val R_OUT = 1
        const val R_OTHER = 2
        const val R_STUCK = 3
        const val R_TIMEOUT = 4
        const val R_REACHED = 5

        val DI = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
        val DJ = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
        val DL = floatArrayOf(1f, 1f, 1f, 1f, 1.4142f, 1.4142f, 1.4142f, 1.4142f)

        /** Candidate tilts: index 0 is "brake", 1 is "flat", then 12 directions at two strengths and the 4 corners. */
        val CX: FloatArray
        val CY: FloatArray
        val NC: Int

        init {
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            xs.add(0f); ys.add(0f)
            xs.add(0f); ys.add(0f)
            for (m in listOf(0.5f, 0.2f)) for (k in 0 until 12) {
                val a = k * Math.PI / 6.0
                xs.add((m * Math.cos(a)).toFloat())
                ys.add((m * Math.sin(a)).toFloat())
            }
            for (sx in listOf(-0.5f, 0.5f)) for (sy in listOf(-0.5f, 0.5f)) { xs.add(sx); ys.add(sy) }
            CX = xs.toFloatArray()
            CY = ys.toFloatArray()
            NC = CX.size
        }

        fun segDist(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
            val ex = x2 - x1
            val ey = y2 - y1
            val len2 = ex * ex + ey * ey
            val t = if (len2 <= 0f) 0f else (((px - x1) * ex + (py - y1) * ey) / len2).coerceIn(0f, 1f)
            return hypot(px - (x1 + ex * t), py - (y1 + ey * t))
        }

        fun fieldAt(f: FloatArray, x: Float, y: Float): Float {
            val i0 = x.roundToInt().coerceIn(0, GW - 1)
            val j0 = y.roundToInt().coerceIn(0, GH - 1)
            var best = INF
            for (dj in -1..1) for (di in -1..1) {
                val i = i0 + di
                val j = j0 + dj
                if (i < 0 || j < 0 || i >= GW || j >= GH) continue
                val v = f[j * GW + i]
                if (v < 1e8f) {
                    val d = v + hypot(x - i, y - j)
                    if (d < best) best = d
                }
            }
            return best
        }
    }
}
