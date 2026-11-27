package com.papertilt.game.core

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * A level ready to play. [par] is the time in seconds to beat for the third star, [outs] how
 * many lost balls end the level, and [limit] the seconds on its clock.
 */
class Built(val world: World, val hint: String?, val par: Float = Levels.defaultPar(world)) {
    var outs = MAX_OUTS
    var limit = par * 3f
}

/**
 * The campaign. Levels 1-4 are hand-made tutorials; the rest are generated from a plan
 * (one row of knobs per level) and a hand-picked seed, so every player gets the same 40 levels.
 *
 * A generated layout is only accepted when it is fair by construction: spawn points are
 * clear of open edges and of holes that would swallow the ball, pencil walls keep their
 * distance from each other and from the rails, rolling any ball straight at its nearest
 * hole does not cost another ball at once, and the level can be solved by sliding the
 * balls into their holes one after another while every other ball stays well on the paper
 * (see [Solver]). A seed that fails any of this is skipped and the next one is tried, so a
 * level never ships with a ball or a hole missing.
 */
object Levels {
    const val COUNT = 40
    const val W = 100f
    const val H = 150f

    /** Balls spawn at least this far from an open edge, and this far from a railed one. */
    internal const val SPAWN_OPEN = 20f
    internal const val SPAWN_RAIL = 12f

    /** Gap between a spawning ball and the rim of a hole that could take it wrongly, or of any moving hole's track. */
    internal const val SPAWN_HOLE = 13f

    /** Gap between a spawning ball and the rim of any other hole. */
    internal const val SPAWN_OWN = 8f

    /** Two pencil walls, or a wall and a rail, are either joined or at least this far apart (the largest ball needs 14.2). */
    internal const val WALL_GAP = 16f

    /** How far the obvious opening move is checked, and the room it must leave at open edges. */
    internal const val OPENING = 40f
    internal const val OPENING_EDGE = 8f

    /** Rim-to-rim distance between two holes. */
    internal const val HOLE_GAP = 10f

    /** At the last level tilt pushes this much harder, and the paper has this much less drag. */
    internal const val MAX_PUSH = 0.5f
    internal const val MAX_SLIP = 0.5f
    internal const val OUTS_TWO_FROM = 12
    internal const val OUTS_ONE_FROM = 34

    private const val HINT_TOGETHER = "All balls roll together.\nPlan the order."
    private const val HINT_SAND = "Sand slows everything down."
    private const val HINT_MOVING = "Some holes like to wander."
    private const val HINT_NO_RAILS = "No rails at all now. Easy does it."

    /**
     * The knobs of one generated level. [edge] and [keep] say how much room the built-in
     * solution must leave: every waiting ball stays [edge] from open edges and [keep] outside
     * the pull of bigger holes. [starts] is how many different balls can go first. The holes
     * that wander are the smallest on a [gentle] level (they can swallow nothing wrongly) and
     * the biggest on a [big] one (they are a moving hazard for every smaller ball).
     */
    internal class Spec(
        val rails: Int, val balls: Int, val holes: Int, val moving: Int, val sand: Int, val walls: Int,
        val edge: Float, val keep: Float, val starts: Int = 1, val gentle: Boolean = false, val big: Boolean = false,
    )

    /**
     * Levels 5..40. Every level has exactly one hole per ball, so the only wrong holes are the
     * other balls' bigger ones. One new thing at a time: the rail count, the ball count, the
     * first sand and each extra moving hole all change on different levels. The ramp is steep:
     * four balls by level 10, no rails by 16, six balls by 19, seven by 33. From level 16 on
     * the rail count goes 0, 1 or 2 so that late levels differ in kind.
     */
    private val SPECS: List<Spec> = listOf(
        //   rails balls holes moving sand walls  edge  keep
        Spec(3, 2, 2, 0, 0, 1, 15f, 7f, 2, true),  // 5 two balls at once
        Spec(3, 3, 3, 0, 0, 1, 15f, 7f),  // 6 three balls
        Spec(3, 3, 3, 0, 0, 2, 15f, 7f),  // 7 
        Spec(2, 3, 3, 0, 0, 2, 15f, 7f),  // 8 two rails
        Spec(2, 3, 3, 0, 1, 2, 15f, 7f),  // 9 first sand
        Spec(2, 4, 4, 0, 1, 2, 15f, 7f),  // 10 four balls
        Spec(2, 4, 4, 1, 1, 2, 14f, 6f, 2, true),  // 11 first moving hole
        Spec(1, 4, 4, 1, 1, 2, 14f, 6f),  // 12 one rail
        Spec(1, 4, 4, 1, 2, 2, 14f, 6f, big = true),  // 13 
        Spec(1, 5, 5, 1, 1, 2, 14f, 6f),  // 14 five balls
        Spec(1, 5, 5, 1, 2, 2, 14f, 6f, big = true),  // 15 
        Spec(0, 5, 5, 1, 1, 2, 14f, 6f, 2),  // 16 no rails
        Spec(0, 5, 5, 2, 1, 2, 14f, 6f),  // 17 second moving hole
        Spec(2, 5, 5, 2, 2, 3, 13f, 6f, big = true),  // 18 
        Spec(2, 6, 6, 2, 2, 3, 13f, 6f),  // 19 six balls
        Spec(1, 6, 6, 2, 2, 3, 13f, 6f, big = true),  // 20 
        Spec(1, 6, 6, 3, 1, 2, 13f, 6f),  // 21 third moving hole
        Spec(0, 6, 6, 3, 1, 2, 13f, 6f, big = true),  // 22 
        Spec(0, 6, 6, 3, 2, 2, 13f, 6f, big = true),  // 23 
        Spec(2, 6, 6, 3, 2, 3, 13f, 6f, big = true),  // 24 
        Spec(1, 6, 6, 3, 2, 3, 13f, 6f, big = true),  // 25 
        Spec(0, 6, 6, 3, 1, 2, 13f, 6f, big = true),  // 26 
        Spec(0, 6, 6, 3, 2, 3, 13f, 5f, big = true),  // 27 
        Spec(1, 6, 6, 3, 2, 3, 13f, 5f, big = true),  // 28 
        Spec(2, 6, 6, 3, 2, 3, 12f, 5f, big = true),  // 29 
        Spec(0, 6, 6, 3, 1, 2, 12f, 5f, big = true),  // 30 
        Spec(1, 6, 6, 3, 2, 3, 12f, 5f, big = true),  // 31 
        Spec(2, 6, 6, 3, 2, 3, 12f, 5f, big = true),  // 32 
        Spec(2, 7, 7, 3, 2, 2, 12f, 5f),  // 33 seven balls
        Spec(1, 7, 7, 3, 2, 2, 12f, 5f, big = true),  // 34 
        Spec(0, 7, 7, 3, 1, 2, 12f, 5f, big = true),  // 35 
        Spec(0, 7, 7, 3, 2, 2, 12f, 5f, big = true),  // 36 
        Spec(2, 7, 7, 3, 2, 3, 12f, 5f, big = true),  // 37 
        Spec(1, 7, 7, 3, 2, 3, 12f, 5f, big = true),  // 38 
        Spec(0, 7, 7, 3, 2, 2, 12f, 5f, big = true),  // 39 
        Spec(0, 7, 7, 3, 1, 2, 12f, 5f, big = true),  // 40 
    )

    /**
     * Which acceptable layout each generated level uses (index 0 is level 5). Picked with the
     * solvability probe so that measured difficulty rises steadily; see SolvabilityProbe.
     */
    private val PICK = intArrayOf(
        3, 0, 4, 17, 8, 2, 35, 7, 15, 9, 409, 669,
        187, 826, 6863, 36130, 35, 13356, 118688, 1417, 45205, 15416, 81634, 33501,
        4194, 66403, 45785, 22215, 7630, 36421, 100464, 2306705, 137289, 11797, 226241, 276053,
    )

    /**
     * Seconds to beat for the third star, levels 1..40. Rule: 1.2 x the median winning time of
     * SolvabilityProbe's "careful" player (a steady but clumsy hand) + 2 s, rounded up, and
     * never under 8 s. A quick, competent run beats it; a dawdling one does not. The times come
     * from a bot, not a person: re-derive the table whenever a layout or the physics changes.
     */
    private val PAR = intArrayOf(
        8, 8, 8, 9, 8, 9, 10, 11, 8, 11,
        12, 12, 9, 12, 10, 14, 14, 11, 17, 17,
        15, 23, 14, 20, 19, 21, 17, 21, 14, 14,
        23, 19, 17, 18, 17, 25, 17, 16, 19, 14,
    )

    /** For the tuning probe only: try this layout number instead of the picked one, on this thread. */
    internal val candidate = ThreadLocal<Int?>()

    private fun firstLevel(test: (Spec) -> Boolean): Int = 5 + SPECS.indexOfFirst(test)

    internal val firstSand: Int = firstLevel { it.sand > 0 }
    internal val firstMoving: Int = firstLevel { it.moving > 0 }
    internal val firstNoRails: Int = firstLevel { it.rails == 0 }

    internal fun spec(n: Int): Spec = SPECS[n.coerceIn(5, COUNT) - 5]

    private fun par(n: Int): Float = PAR[n - 1].toFloat()

    /** 0 on level 1, 1 on the last level, rising fastest early so that each new level is felt. */
    internal fun hardness(n: Int): Float = Math.pow(((n - 1).coerceIn(0, COUNT - 1) / (COUNT - 1f)).toDouble(), 0.7).toFloat()

    /** Lost balls that end level [n]: three at first, then two, then a single one. */
    fun outsAllowed(n: Int): Int = when {
        n < OUTS_TWO_FROM -> 3
        n < OUTS_ONE_FROM -> 2
        else -> 1
    }

    /**
     * Level [n] with its difficulty applied. Beyond the layout, every level is a little harder
     * than the one before: the balls are pushed harder and the paper is more slippery, later
     * levels forgive fewer outs, and the clock allows less slack over par.
     */
    fun build(n: Int): Built = layoutOf(n).also {
        val d = hardness(n)
        it.world.push = 1f + MAX_PUSH * d
        it.world.grip = 1f - MAX_SLIP * d
        it.outs = outsAllowed(n)
        it.limit = ceil(it.par * (3f - 1.4f * d))
    }

    private fun layoutOf(n: Int): Built = when (n) {
        1 -> Built(
            World(W, H).apply {
                rails(true, true, true, true)
                holes.add(Hole(50f, 40f, Size.M))
                balls.add(Ball(50f, 115f, Size.M, 0))
            },
            "Tilt your phone to roll\nthe ball into the hole.",
            par(1),
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
            par(2),
        )
        3 -> Built(
            World(W, H).apply {
                rails(false, true, false, true)
                holes.add(Hole(50f, 32f, Size.M))
                balls.add(Ball(50f, 118f, Size.M, 0))
            },
            "No rail on a dashed edge.\nRoll off the paper and you're OUT.",
            par(3),
        )
        4 -> Built(
            World(W, H).apply {
                rails(true, false, false, true)
                walls.add(Wall(30f, 75f, 70f, 75f))
                walls.add(Wall(78f, 20f, 78f, 50f))
                holes.add(Hole(64f, 34f, Size.S))
                holes.add(Hole(28f, 34f, Size.M))
                balls.add(Ball(30f, 125f, Size.S, 0))
                balls.add(Ball(72f, 125f, Size.M, 1))
            },
            "Pencil walls stop a ball.\nLean on them.",
            par(4),
        )
        else -> {
            val w = layout(n, candidate.get() ?: PICK[n.coerceIn(5, COUNT) - 5])
            val hint = when (n) {
                5 -> HINT_TOGETHER
                firstSand -> HINT_SAND
                firstMoving -> HINT_MOVING
                firstNoRails -> HINT_NO_RAILS
                else -> null
            }
            Built(w, hint, if (n in 1..COUNT) par(n) else defaultPar(w))
        }
    }

    /** A fully railed page of loose balls, shown behind the menu. */
    fun menuWorld(height: Float = H): World = World(W, height).apply {
        rails(true, true, true, true)
        val sizes = listOf(Size.L, Size.M, Size.S, Size.M, Size.S, Size.L, Size.S)
        for ((i, s) in sizes.withIndex()) {
            balls.add(Ball(16f + (i % 4) * 22f, 30f + (i / 4) * 60f + i * 5f, s, i))
        }
    }

    /** A rough par from the layout: a few seconds per ball plus the distance each has to travel. */
    fun defaultPar(w: World): Float {
        var dist = 0f
        for (b in w.balls) {
            dist += w.holes.filter { it.size == b.size }.minOfOrNull { hypot(it.baseX - b.x, it.baseY - b.y) } ?: 0f
        }
        return 6f + 4f * w.balls.size + dist / 15f
    }

    /** Layout number [pick] of generated level [n]; if it is not acceptable, the next one that is. */
    private fun layout(n: Int, pick: Int): World {
        val sp = spec(n)
        var v = pick
        while (true) {
            generate(sp, n * 7919 + v * 104729 + 17)?.let { return it }
            v++
        }
    }

    /** True when the layout picked for generated level [n] is acceptable as it is, so nothing had to be skipped. */
    internal fun pickIsAcceptable(n: Int): Boolean = generate(spec(n), n * 7919 + PICK[n - 5] * 104729 + 17) != null

    /** The numbers of the first [count] acceptable layouts of level [n], for the tuning probe. */
    internal fun acceptable(n: Int, count: Int): List<Int> {
        val out = ArrayList<Int>()
        var v = 0
        while (out.size < count) {
            if (generate(spec(n), n * 7919 + v * 104729 + 17) != null) out.add(v)
            v++
        }
        return out
    }

    // ---------------------------------------------------------------- geometry helpers

    internal fun segDist(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val ex = x2 - x1
        val ey = y2 - y1
        val len2 = ex * ex + ey * ey
        val t = if (len2 <= 0f) 0f else (((px - x1) * ex + (py - y1) * ey) / len2).coerceIn(0f, 1f)
        return hypot(px - (x1 + ex * t), py - (y1 + ey * t))
    }

    /** Closest distance between two segments (either may be a single point). */
    internal fun segSeg(a: FloatArray, b: FloatArray): Float {
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

    /** The line a hole's centre travels along (a point for a hole that stays put). */
    internal fun track(h: Hole) = floatArrayOf(h.baseX - h.ampX, h.baseY - h.ampY, h.baseX + h.ampX, h.baseY + h.ampY)

    private fun seg(wl: Wall) = floatArrayOf(wl.x1, wl.y1, wl.x2, wl.y2)

    /** Distance from a point to the nearest place a hole's centre can be. */
    internal fun trackDist(h: Hole, x: Float, y: Float): Float =
        segDist(x, y, h.baseX - h.ampX, h.baseY - h.ampY, h.baseX + h.ampX, h.baseY + h.ampY)

    // ---------------------------------------------------------------- the generator

    /** One attempt at a level; null when anything could not be placed or the result is not fair. */
    private fun generate(sp: Spec, seed: Int): World? {
        val rng = Random(seed)
        val w = World(W, H)
        val sides = listOf(0, 1, 2, 3).shuffled(rng).take(sp.rails)
        w.rails(0 in sides, 1 in sides, 2 in sides, 3 in sides)
        val rt = w.railed[0]
        val rr = w.railed[1]
        val rb = w.railed[2]
        val rl = w.railed[3]

        // Sizes: at least two different ball sizes, and every spare hole is a wrong hole for some ball.
        val ballSizes = MutableList(sp.balls) { Size.values()[rng.nextInt(3)] }
        if (ballSizes.distinct().size < 2) ballSizes[0] = Size.values()[(ballSizes[0].ordinal + 1 + rng.nextInt(2)) % 3]
        if (sp.gentle) ballSizes.sortBy { it.ordinal }
        if (sp.big) ballSizes.sortByDescending { it.ordinal }
        val smallest = ballSizes.minOf { it.ordinal }
        val holeSizes = ballSizes + List(sp.holes - sp.balls) { Size.values()[smallest + 1 + rng.nextInt(2 - smallest)] }

        // Holes. The first few wander; on a gentle level those are the smallest, which swallow nothing wrongly.
        for ((i, s) in holeSizes.withIndex()) {
            var placed = false
            for (attempt in 0 until 200) {
                var ax = 0f
                var ay = 0f
                if (i < sp.moving) {
                    val amp = 9f + rng.nextFloat() * 6f
                    if (rng.nextBoolean()) ax = amp else ay = amp
                }
                val x0 = (if (rl) 14f else 18f) + ax
                val x1 = W - (if (rr) 14f else 18f) - ax
                val y0 = (if (rt) 14f else 18f) + ay
                val y1 = H - (if (rb) 14f else 18f) - ay
                val cand = Hole(
                    x0 + rng.nextFloat() * (x1 - x0), y0 + rng.nextFloat() * (y1 - y0), s, ax, ay,
                    4.5f + rng.nextFloat() * 2f, rng.nextFloat(),
                )
                if (w.holes.all { o -> segSeg(track(cand), track(o)) >= cand.r + o.r + HOLE_GAP }) {
                    w.holes.add(cand)
                    placed = true
                    break
                }
            }
            if (!placed) return null
        }

        // Balls. Where both edges of an axis are open the balls start in a band, not spread from
        // edge to edge: they slide as one formation and it needs room to move.
        val bandW = if (!rl && !rr) 52f else W
        val bandH = if (!rt && !rb) 80f else H
        val bx = rng.nextFloat() * (W - bandW)
        val by = rng.nextFloat() * (H - bandH)
        val sx0 = max(bx, if (rl) SPAWN_RAIL else SPAWN_OPEN + 0.5f)
        val sx1 = min(bx + bandW, W - (if (rr) SPAWN_RAIL else SPAWN_OPEN + 0.5f))
        val sy0 = max(by, if (rt) SPAWN_RAIL else SPAWN_OPEN + 0.5f)
        val sy1 = min(by + bandH, H - (if (rb) SPAWN_RAIL else SPAWN_OPEN + 0.5f))
        for ((id, s) in ballSizes.withIndex()) {
            var placed = false
            for (attempt in 0 until 300) {
                val x = sx0 + rng.nextFloat() * (sx1 - sx0)
                val y = sy0 + rng.nextFloat() * (sy1 - sy0)
                val clearOfHoles = w.holes.all { h ->
                    val gap = if (h.moving || h.size.ordinal > s.ordinal) SPAWN_HOLE else SPAWN_OWN
                    trackDist(h, x, y) >= h.r + s.ballR + gap
                }
                if (clearOfHoles && w.balls.all { hypot(x - it.x, y - it.y) >= it.r + s.ballR + 4f }) {
                    w.balls.add(Ball(x, y, s, id))
                    placed = true
                    break
                }
            }
            if (!placed) return null
        }

        // Pencil walls: free-standing, an L of two, or a shelf growing out of a rail.
        val inner = ArrayList<Wall>()
        while (inner.size < sp.walls) {
            var placed = false
            for (attempt in 0 until 120) {
                val group = ArrayList<Wall>()
                val kind = rng.nextInt(10)
                if (kind < 2 && sp.walls - inner.size >= 2) {
                    val cx = 24f + rng.nextFloat() * 52f
                    val cy = 28f + rng.nextFloat() * 94f
                    val a = (14f + rng.nextFloat() * 10f) * (if (rng.nextBoolean()) 1f else -1f)
                    val b = (14f + rng.nextFloat() * 10f) * (if (rng.nextBoolean()) 1f else -1f)
                    group.add(Wall(cx, cy, cx + a, cy))
                    group.add(Wall(cx, cy, cx, cy + b))
                } else if (kind < 4 && sp.rails > 0) {
                    val len = 16f + rng.nextFloat() * 12f
                    when (sides[rng.nextInt(sides.size)]) {
                        0 -> { val x = 26f + rng.nextFloat() * 48f; group.add(Wall(x, 0f, x, len)) }
                        1 -> { val y = 28f + rng.nextFloat() * 94f; group.add(Wall(W, y, W - len, y)) }
                        2 -> { val x = 26f + rng.nextFloat() * 48f; group.add(Wall(x, H, x, H - len)) }
                        else -> { val y = 28f + rng.nextFloat() * 94f; group.add(Wall(0f, y, len, y)) }
                    }
                } else {
                    val len = 16f + rng.nextFloat() * 14f
                    val cx = 22f + rng.nextFloat() * 56f
                    val cy = 26f + rng.nextFloat() * 98f
                    val (dx, dy) = when (rng.nextInt(3)) {
                        0 -> 1f to 0f
                        1 -> 0f to 1f
                        else -> 0.707f to (if (rng.nextBoolean()) 0.707f else -0.707f)
                    }
                    group.add(Wall(cx - dx * len / 2, cy - dy * len / 2, cx + dx * len / 2, cy + dy * len / 2))
                }
                if (group.all { wallFits(w, inner, it) }) {
                    inner.addAll(group)
                    placed = true
                    break
                }
            }
            if (!placed) return null
        }
        w.walls.addAll(inner)

        // Sand, never under a spawn point.
        while (w.sand.size < sp.sand) {
            var placed = false
            for (attempt in 0 until 60) {
                val zw = 22f + rng.nextFloat() * 14f
                val zh = 16f + rng.nextFloat() * 10f
                val z = Zone(4f + rng.nextFloat() * (92f - zw), 4f + rng.nextFloat() * (142f - zh), zw, zh)
                val apart = w.sand.all { o -> z.x > o.x + o.w + 6f || o.x > z.x + z.w + 6f || z.y > o.y + o.h + 6f || o.y > z.y + z.h + 6f }
                val offSpawns = w.balls.all { b ->
                    b.x < z.x - b.r - 2f || b.x > z.x + z.w + b.r + 2f || b.y < z.y - b.r - 2f || b.y > z.y + z.h + b.r + 2f
                }
                if (apart && offSpawns) {
                    w.sand.add(z)
                    placed = true
                    break
                }
            }
            if (!placed) return null
        }

        val solver = Solver(w, inner, sp.edge, sp.keep)
        if (!solver.openingsAreSafe() || solver.starts(sp.starts) < sp.starts) return null
        return w
    }

    /**
     * A wall is fine when every point of it is [WALL_GAP] from the paper's edge (or one end sits
     * exactly on a rail and the rest is clear), [WALL_GAP] from every other wall, and clear of
     * holes, their tracks and spawn points. The two arms of an L are checked one by one before
     * either is added, so they are not measured against each other.
     */
    private fun wallFits(w: World, inner: List<Wall>, wl: Wall): Boolean {
        val s = seg(wl)
        val onRail = (wl.x1 == 0f && w.railed[3]) || (wl.x1 == W && w.railed[1]) || (wl.y1 == 0f && w.railed[0]) || (wl.y1 == H && w.railed[2])
        fun inside(x: Float, y: Float) = x >= WALL_GAP && x <= W - WALL_GAP && y >= WALL_GAP && y <= H - WALL_GAP
        if (!inside(wl.x2, wl.y2)) return false
        if (!onRail && !inside(wl.x1, wl.y1)) return false
        if (onRail) {
            // a shelf must not make a narrow pocket with the neighbouring edge
            val alongX = wl.y1 == wl.y2
            if (alongX && (wl.y1 < 24f || wl.y1 > H - 24f)) return false
            if (!alongX && (wl.x1 < 24f || wl.x1 > W - 24f)) return false
        }
        if (inner.any { segSeg(s, seg(it)) < WALL_GAP }) return false
        if (w.holes.any { segSeg(s, track(it)) < it.r + 6f }) return false
        if (w.balls.any { segDist(it.x, it.y, wl.x1, wl.y1, wl.x2, wl.y2) < it.r + 5f }) return false
        return true
    }

    /**
     * Proof that a layout can be finished the plain way. All balls feel the same tilt, so until
     * one meets a rail they move as a rigid formation. The solver looks for an order in which
     * each ball in turn is slid straight to a hole of its size while every other ball stays
     * [edge] away from the open edges, [keep] outside the pull of any hole that would swallow
     * it, off the sand and off the pencil walls (so the formation really does stay rigid; rails
     * simply stop a ball). Wandering holes are avoided along their whole track.
     */
    internal class Solver(private val w: World, private val inner: List<Wall>, private val edge: Float, private val keep: Float) {
        private val nb = w.balls.size
        private val nh = w.holes.size
        private val alive = BooleanArray(nb) { true }
        private val filled = BooleanArray(nh)
        private var nodes = 0

        /**
         * The obvious opening must not cost a ball: for every ball, rolling it straight at the
         * nearest hole of its size for [OPENING] units (or all the way, if that is nearer) keeps
         * every other ball out of the pull of bigger holes and [OPENING_EDGE] away from open
         * edges. The player may still have to change course; they get time to see it coming.
         */
        fun openingsAreSafe(): Boolean {
            for (i in 0 until nb) {
                val b = w.balls[i]
                var best = Float.MAX_VALUE
                var tx = 0f
                var ty = 0f
                for (h in w.holes) {
                    if (h.size != b.size) continue
                    // nearest point of the hole's track
                    val ex = 2f * h.ampX
                    val ey = 2f * h.ampY
                    val len2 = ex * ex + ey * ey
                    val t = if (len2 <= 0f) 0f else (((b.x - (h.baseX - h.ampX)) * ex + (b.y - (h.baseY - h.ampY)) * ey) / len2).coerceIn(0f, 1f)
                    val hx = h.baseX - h.ampX + ex * t
                    val hy = h.baseY - h.ampY + ey * t
                    val d = hypot(hx - b.x, hy - b.y)
                    if (d < best) { best = d; tx = hx; ty = hy }
                }
                if (best == Float.MAX_VALUE || best < 1f) continue
                val reach = min(best, OPENING)
                val ux = (tx - b.x) / best
                val uy = (ty - b.y) / best
                var s = 2f
                while (s <= reach) {
                    for (j in 0 until nb) {
                        if (j == i) continue
                        val o = w.balls[j]
                        val m = o.r + World.WALL_T
                        var x = o.x + ux * s
                        var y = o.y + uy * s
                        if (w.railed[3] && x < m) x = m
                        if (w.railed[1] && x > w.w - m) x = w.w - m
                        if (w.railed[0] && y < m) y = m
                        if (w.railed[2] && y > w.h - m) y = w.h - m
                        if (!w.railed[3] && x < OPENING_EDGE || !w.railed[1] && x > w.w - OPENING_EDGE) return false
                        if (!w.railed[0] && y < OPENING_EDGE || !w.railed[2] && y > w.h - OPENING_EDGE) return false
                        for (h in w.holes) {
                            if (h.size.ordinal > o.size.ordinal && trackDist(h, x, y) < h.r + o.r * 0.3f + 2f) return false
                        }
                    }
                    s += 2f
                }
            }
            return true
        }

        /** How many different balls can go first in a complete solution (counting stops at [want]). */
        fun starts(want: Int): Int {
            val px = FloatArray(nb) { w.balls[it].x }
            val py = FloatArray(nb) { w.balls[it].y }
            var count = 0
            for (i in 0 until nb) {
                nodes = 0
                if (solve(px, py, nb, i)) count++
                if (count >= want) break
            }
            return count
        }

        private fun solve(px: FloatArray, py: FloatArray, left: Int, only: Int): Boolean {
            if (left == 0) return true
            if (++nodes > 300) return false
            val nx = FloatArray(nb)
            val ny = FloatArray(nb)
            for (i in 0 until nb) {
                if (!alive[i] || (only >= 0 && i != only)) continue
                val b = w.balls[i]
                for (hi in 0 until nh) {
                    val h = w.holes[hi]
                    if (filled[hi] || h.size != b.size) continue
                    val stops = if (h.moving) 5 else 1
                    for (s in 0 until stops) {
                        val k = if (h.moving) -1f + 0.5f * s else 0f
                        if (!slide(i, h.baseX + h.ampX * k - px[i], h.baseY + h.ampY * k - py[i], px, py, nx, ny)) continue
                        alive[i] = false
                        filled[hi] = true
                        val ok = solve(nx, ny, left - 1, -1)
                        alive[i] = true
                        filled[hi] = false
                        if (ok) return true
                    }
                }
            }
            return false
        }

        /** Move every loose ball by ([dx], [dy]); ball [i] is the one being delivered. False if any ball gets into trouble. */
        private fun slide(i: Int, dx: Float, dy: Float, px: FloatArray, py: FloatArray, nx: FloatArray, ny: FloatArray): Boolean {
            val steps = max(1, ceil(hypot(dx, dy) / 2f).toInt())
            for (k in 1..steps) {
                val t = k.toFloat() / steps
                for (j in 0 until nb) {
                    if (!alive[j]) continue
                    val b = w.balls[j]
                    val m = b.r + World.WALL_T
                    var x = px[j] + dx * t
                    var y = py[j] + dy * t
                    if (w.railed[3] && x < m) x = m
                    if (w.railed[1] && x > w.w - m) x = w.w - m
                    if (w.railed[0] && y < m) y = m
                    if (w.railed[2] && y > w.h - m) y = w.h - m
                    if (j != i) {
                        if (!w.railed[3] && x < edge || !w.railed[1] && x > w.w - edge) return false
                        if (!w.railed[0] && y < edge || !w.railed[2] && y > w.h - edge) return false
                    }
                    for (hj in 0 until nh) {
                        if (filled[hj]) continue
                        val h = w.holes[hj]
                        if (h.size.ordinal > b.size.ordinal) {
                            if (trackDist(h, x, y) < h.r + b.r * 0.3f + keep) return false
                        } else if (h.size == b.size && j != i) {
                            if (trackDist(h, x, y) < h.r + b.r * 0.3f + 1f) return false
                        }
                    }
                    for (wl in inner) if (segDist(x, y, wl.x1, wl.y1, wl.x2, wl.y2) < m + 1f) return false
                    for (z in w.sand) if (z.contains(x, y)) return false
                    nx[j] = x
                    ny[j] = y
                }
            }
            for (j in 0 until nb) {
                if (!alive[j] || j == i) continue
                for (l in j + 1 until nb) {
                    if (!alive[l] || l == i) continue
                    if (hypot(nx[j] - nx[l], ny[j] - ny[l]) < w.balls[j].r + w.balls[l].r) return false
                }
            }
            return true
        }
    }
}
