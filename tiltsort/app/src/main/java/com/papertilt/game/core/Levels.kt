package com.papertilt.game.core

import kotlin.math.hypot
import kotlin.random.Random

class Built(val world: World, val hint: String?)

/**
 * The campaign. Levels 1-4 are hand-made tutorials; the rest are generated
 * from the level number, so every player gets the same 40 levels.
 */
object Levels {
    const val COUNT = 40
    const val W = 100f
    const val H = 150f

    fun build(n: Int): Built = when (n) {
        1 -> Built(
            World(W, H).apply {
                rails(true, true, true, true)
                holes.add(Hole(50f, 40f, Size.M))
                balls.add(Ball(50f, 115f, Size.M, 0))
            },
            "Tilt your phone to roll\nthe ball into the hole.",
        )
        2 -> Built(
            World(W, H).apply {
                rails(true, true, true, true)
                holes.add(Hole(30f, 40f, Size.L))
                holes.add(Hole(70f, 40f, Size.S))
                balls.add(Ball(30f, 120f, Size.S, 0))
                balls.add(Ball(70f, 120f, Size.L, 1))
            },
            "Match the size! A small ball lost\nin a big hole is an OUT.",
        )
        3 -> Built(
            World(W, H).apply {
                rails(false, true, false, true)
                holes.add(Hole(50f, 32f, Size.M))
                holes.add(Hole(24f, 75f, Size.L))
                balls.add(Ball(50f, 118f, Size.M, 0))
            },
            "No rail on a dashed edge.\nRoll off the paper and you're OUT.",
        )
        4 -> Built(
            World(W, H).apply {
                rails(false, false, false, true)
                walls.add(Wall(30f, 75f, 70f, 75f))
                walls.add(Wall(78f, 20f, 78f, 50f))
                holes.add(Hole(64f, 34f, Size.S))
                holes.add(Hole(28f, 34f, Size.M))
                holes.add(Hole(50f, 100f, Size.L))
                balls.add(Ball(30f, 125f, Size.S, 0))
                balls.add(Ball(72f, 125f, Size.M, 1))
            },
            "Pencil walls stop a ball.\nLean on them.",
        )
        else -> generate(n)
    }

    /** A fully railed page of loose balls, shown behind the menu. */
    fun menuWorld(height: Float = H): World = World(W, height).apply {
        rails(true, true, true, true)
        val sizes = listOf(Size.L, Size.M, Size.S, Size.M, Size.S, Size.L, Size.S)
        for ((i, s) in sizes.withIndex()) {
            balls.add(Ball(16f + (i % 4) * 22f, 30f + (i / 4) * 60f + i * 5f, s, i))
        }
    }

    private fun samples(h: Hole): List<Pair<Float, Float>> =
        if (!h.moving) listOf(h.baseX to h.baseY)
        else listOf(-1f, -0.5f, 0f, 0.5f, 1f).map { (h.baseX + h.ampX * it) to (h.baseY + h.ampY * it) }

    private fun generate(n: Int): Built {
        val rng = Random(n * 7919 + 17)
        val d = ((n - 5) / 35f).coerceIn(0f, 1f)
        val w = World(W, H)

        val nRails = when {
            n < 9 -> 3
            n < 14 -> 2
            n < 22 -> 1
            else -> 0
        }
        val sides = listOf(0, 1, 2, 3).shuffled(rng).take(nRails)
        w.rails(0 in sides, 1 in sides, 2 in sides, 3 in sides)

        val nBalls = (2 + (d * 4.2f).toInt()).coerceAtMost(6)
        val nHoles = (nBalls + 1 + (d * 3f).toInt()).coerceAtMost(9)
        val nMoving = if (n < 14) 0 else 1 + (n - 14) / 9

        val ballSizes = MutableList(nBalls) { Size.values()[rng.nextInt(3)] }
        if (ballSizes.all { it == Size.L }) ballSizes[0] = Size.S
        val holeSizes = ballSizes + List(nHoles - nBalls) { Size.values()[rng.nextInt(3)] }

        for ((i, s) in holeSizes.withIndex()) {
            for (attempt in 0 until 400) {
                var ax = 0f
                var ay = 0f
                if (i < nMoving) {
                    val amp = 9f + rng.nextFloat() * 6f
                    if (rng.nextBoolean()) ax = amp else ay = amp
                }
                val x = 14f + ax + rng.nextFloat() * (72f - 2 * ax)
                val y = 16f + ay + rng.nextFloat() * (118f - 2 * ay)
                val cand = Hole(x, y, s, ax, ay, 4.5f + rng.nextFloat() * 2f, rng.nextFloat())
                val ok = w.holes.all { o ->
                    samples(o).all { (ox, oy) ->
                        samples(cand).all { (cx, cy) -> hypot(cx - ox, cy - oy) >= cand.r + o.r + 9f }
                    }
                }
                if (ok) {
                    w.holes.add(cand)
                    break
                }
            }
        }
        // Every ball needs a hole of its own size; drop balls whose hole could not be placed.
        val free = w.holes.map { it.size }.toMutableList()
        var id = 0
        for (s in ballSizes) {
            if (!free.remove(s)) continue
            for (attempt in 0 until 600) {
                val x = 12f + rng.nextFloat() * 76f
                val y = 12f + rng.nextFloat() * 126f
                val clearOfHoles = w.holes.all { h ->
                    samples(h).all { (hx, hy) -> hypot(x - hx, y - hy) >= h.r + s.ballR + 5f }
                }
                val clearOfBalls = w.balls.all { hypot(x - it.x, y - it.y) >= it.r + s.ballR + 4f }
                if (clearOfHoles && clearOfBalls) {
                    w.balls.add(Ball(x, y, s, id++))
                    break
                }
            }
        }

        val nWalls = 1 + rng.nextInt(2) + if (d > 0.5f) 1 else 0
        for (k in 0 until nWalls) {
            for (attempt in 0 until 80) {
                val len = 16f + rng.nextFloat() * 14f
                val cx = 22f + rng.nextFloat() * 56f
                val cy = 26f + rng.nextFloat() * 98f
                val (dx, dy) = when (rng.nextInt(3)) {
                    0 -> 1f to 0f
                    1 -> 0f to 1f
                    else -> 0.707f to (if (rng.nextBoolean()) 0.707f else -0.707f)
                }
                val x1 = cx - dx * len / 2
                val y1 = cy - dy * len / 2
                val x2 = cx + dx * len / 2
                val y2 = cy + dy * len / 2
                var ok = true
                for (q in 0..8) {
                    val px = x1 + (x2 - x1) * q / 8f
                    val py = y1 + (y2 - y1) * q / 8f
                    if (w.holes.any { h -> samples(h).any { (hx, hy) -> hypot(px - hx, py - hy) < h.r + 5f } }) ok = false
                    if (w.balls.any { hypot(px - it.x, py - it.y) < it.r + 5f }) ok = false
                }
                if (ok) {
                    w.walls.add(Wall(x1, y1, x2, y2))
                    break
                }
            }
        }

        if (n >= 10) {
            repeat(1 + n % 2) {
                val zw = 22f + rng.nextFloat() * 14f
                val zh = 16f + rng.nextFloat() * 10f
                w.sand.add(Zone(4f + rng.nextFloat() * (92f - zw), 4f + rng.nextFloat() * (142f - zh), zw, zh))
            }
        }

        val hint = when (n) {
            5 -> "All balls roll together.\nPlan the order."
            10 -> "Sand slows everything down."
            14 -> "Some holes like to wander."
            22 -> "No rails at all now. Easy does it."
            else -> null
        }
        return Built(w, hint)
    }
}
