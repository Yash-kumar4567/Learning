package com.papertilt.game.core

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

enum class FxKind { BUMP, GOOD, BAD, WIN, LOSE, SPAWN, SHOVE, POINT, MEND }

/** Something the screen should react to (sound, floating text). [n] is points or a player index. */
class Fx(val kind: FxKind, val x: Float, val y: Float, val n: Int = 0)

const val MAX_OUTS = 3
private const val RESPAWN_T = 0.8f

/** Campaign level: put every ball in a hole of its size with fewer than three outs. */
class SoloGame(val level: Int) {
    private val built = Levels.build(level)
    val world: World = built.world
    val hint: String? = built.hint

    /** Seconds to beat for the third star. */
    val par: Float = built.par
    val fx = ArrayList<Fx>()
    var outs = 0
    var time = 0f
    var won = false
    var lost = false
    private val respawn = HashMap<Ball, Float>()

    val over: Boolean get() = won || lost

    /** Balls already home; drives the rising chime. */
    val home: Int get() = world.balls.count { it.state == BallState.HOME || (it.state == BallState.SINKING && it.sunkOk) }

    /** One star each: [GOAL_SORTED] finished, [GOAL_CLEAN] no outs, [GOAL_FAST] at or under par. */
    val goals: Int
        get() = if (!won) 0 else GOAL_SORTED or (if (outs == 0) GOAL_CLEAN else 0) or (if (time <= par) GOAL_FAST else 0)
    val stars: Int get() = goals.countOneBits()

    fun step(dt: Float, tx: Float, ty: Float) {
        if (over) return
        time += dt
        world.step(dt, tx, ty)
        for (e in world.events) {
            when (e.type) {
                EvType.BUMP -> fx.add(Fx(FxKind.BUMP, e.ball.x, e.ball.y))
                EvType.SINK_OK -> fx.add(Fx(FxKind.GOOD, e.ball.x, e.ball.y))
                EvType.SINK_BAD, EvType.FALL -> {
                    outs++
                    fx.add(Fx(FxKind.BAD, e.ball.x.coerceIn(8f, world.w - 8f), e.ball.y.coerceIn(8f, world.h - 8f)))
                    respawn[e.ball] = RESPAWN_T
                }
            }
        }
        world.events.clear()
        if (outs >= MAX_OUTS) {
            lost = true
            fx.add(Fx(FxKind.LOSE, 0f, 0f))
            return
        }
        val it = respawn.entries.iterator()
        while (it.hasNext()) {
            val en = it.next()
            en.setValue(en.value - dt)
            if (en.value <= 0f && en.key.state == BallState.GONE) {
                en.key.respawn()
                fx.add(Fx(FxKind.SPAWN, en.key.x, en.key.y))
                it.remove()
            }
        }
        if (world.balls.all { it.state == BallState.HOME }) {
            won = true
            fx.add(Fx(FxKind.WIN, 0f, 0f))
        }
    }

    companion object {
        const val GOAL_SORTED = 1
        const val GOAL_CLEAN = 2
        const val GOAL_FAST = 4
    }
}

/**
 * Endless mode: nine holes, balls keep arriving, three outs and it is over.
 *
 * Each sink adds a link to the chain (up to x5) and refills its timer; when the timer
 * runs out the chain loses a link, so waiting costs points. Ten sinks without an out
 * erase one out.
 */
class RushGame(seed: Long = System.nanoTime()) {
    val world = World(Levels.W, Levels.H, holesFill = false)
    val fx = ArrayList<Fx>()
    var score = 0
    var streak = 0
    var sunk = 0
    var outs = 0
    var over = false
    var chain = 0
    var chainT = 0f
    private val rng = Random(seed)
    private var spawnTimer = 0.4f
    private var nextId = 0
    private val spots = listOf(
        36f to 52.5f, 64f to 52.5f, 36f to 97.5f, 64f to 97.5f, 50f to 52.5f, 50f to 97.5f,
    )

    init {
        val sizes = (List(3) { Size.S } + List(3) { Size.M } + List(3) { Size.L }).shuffled(rng)
        var i = 0
        for (y in listOf(30f, 75f, 120f)) for (x in listOf(22f, 50f, 78f)) world.holes.add(Hole(x, y, sizes[i++]))
    }

    val multiplier: Int get() = 1 + chain

    /** Seconds a link of the chain lasts; it shortens as the run goes on. */
    val chainWindow: Float get() = max(CHAIN_MIN_T, CHAIN_T - sunk * 0.04f)

    fun step(dt: Float, tx: Float, ty: Float) {
        if (over) return
        world.step(dt, tx, ty)
        for (e in world.events) {
            when (e.type) {
                EvType.BUMP -> fx.add(Fx(FxKind.BUMP, e.ball.x, e.ball.y))
                EvType.SINK_OK -> {
                    val pts = 10 * multiplier
                    chain = min(MAX_CHAIN, chain + 1)
                    streak++
                    sunk++
                    score += pts
                    chainT = chainWindow
                    fx.add(Fx(FxKind.GOOD, e.ball.x, e.ball.y, pts))
                    if (streak % MEND_EVERY == 0 && outs > 0) {
                        outs--
                        fx.add(Fx(FxKind.MEND, e.ball.x, e.ball.y))
                    }
                }
                EvType.SINK_BAD, EvType.FALL -> {
                    outs++
                    streak = 0
                    chain = 0
                    fx.add(Fx(FxKind.BAD, e.ball.x.coerceIn(8f, world.w - 8f), e.ball.y.coerceIn(8f, world.h - 8f)))
                }
            }
        }
        world.events.clear()
        world.balls.removeAll { it.state == BallState.GONE }
        if (outs >= MAX_OUTS) {
            over = true
            fx.add(Fx(FxKind.LOSE, 0f, 0f))
            return
        }
        if (chain > 0) {
            chainT -= dt
            if (chainT <= 0f) {
                chain--
                chainT = chainWindow
            }
        }
        spawnTimer -= dt
        if (world.balls.isEmpty()) spawnTimer = min(spawnTimer, 0.3f)
        val cap = min(6, 3 + sunk / 6)
        if (spawnTimer <= 0f && world.balls.size < cap) {
            val size = Size.values()[rng.nextInt(3)]
            val open = spots.filter { (sx, sy) ->
                world.balls.all { hypot(it.x - sx, it.y - sy) > it.r + size.ballR + 3f }
            }
            if (open.isNotEmpty()) {
                val (sx, sy) = open[rng.nextInt(open.size)]
                val b = Ball(sx, sy, size, nextId++)
                b.state = BallState.SPAWNING
                world.balls.add(b)
                fx.add(Fx(FxKind.SPAWN, sx, sy))
                spawnTimer = max(1.0f, 2.6f - sunk * 0.04f)
            }
        }
    }

    companion object {
        const val MAX_CHAIN = 4
        const val CHAIN_T = 5.5f
        const val CHAIN_MIN_T = 3f
        const val MEND_EVERY = 10
    }
}

/**
 * Two players, one phone, held between them. Player 0 sits at the bottom edge and
 * player 1 at the top. A marble that leaves through your opponent's end is your point.
 */
class TugGame {
    val world = World(Levels.W, Levels.H, holesFill = false)
    val fx = ArrayList<Fx>()
    val score = IntArray(2)
    val cooldown = FloatArray(2)
    var winner = -1
    private val respawn = HashMap<Ball, Float>()

    init {
        world.rails(false, true, false, true)
        // Angled pegs: a marble pressed against one always slides off instead of resting on it.
        world.walls.add(Wall(19f, 40f, 33f, 48f))
        world.walls.add(Wall(67f, 48f, 81f, 40f))
        world.walls.add(Wall(19f, 110f, 33f, 102f))
        world.walls.add(Wall(67f, 102f, 81f, 110f))
        world.walls.add(Wall(46f, 18f, 54f, 28f))
        world.walls.add(Wall(54f, 122f, 46f, 132f))
        world.balls.add(Ball(27f, 75f, Size.S, 0))
        world.balls.add(Ball(51f, 75f, Size.L, 1))
        world.balls.add(Ball(74f, 75f, Size.M, 2))
    }

    /**
     * Push every marble away from [player]'s end. A marble right at their edge is stopped
     * dead and kicked hardest; one at the far end is barely touched.
     */
    fun shove(player: Int) {
        if (winner >= 0 || cooldown[player] > 0f) return
        cooldown[player] = SHOVE_COOLDOWN
        val away = if (player == 0) -1f else 1f
        for (b in world.balls) {
            if (b.state != BallState.ROLLING) continue
            val near = (if (player == 0) b.y / world.h else 1f - b.y / world.h).coerceIn(0f, 1f)
            if (b.vy * away < 0f) b.vy *= 1f - near
            b.vy += away * SHOVE_POWER * near
        }
        fx.add(Fx(FxKind.SHOVE, world.w / 2, if (player == 0) world.h else 0f, player))
    }

    fun step(dt: Float, tx: Float, ty: Float) {
        if (winner >= 0) return
        for (p in 0..1) cooldown[p] = max(0f, cooldown[p] - dt)
        world.step(dt, tx, ty)
        for (e in world.events) {
            when (e.type) {
                EvType.BUMP -> fx.add(Fx(FxKind.BUMP, e.ball.x, e.ball.y))
                EvType.FALL -> if (winner < 0) {
                    val scorer = if (e.ball.y < world.h / 2) 0 else 1
                    score[scorer]++
                    fx.add(Fx(FxKind.POINT, e.ball.x.coerceIn(10f, world.w - 10f), e.ball.y.coerceIn(10f, world.h - 10f), scorer))
                    // Marbles lost together come back one after another, not as a clump.
                    respawn[e.ball] = RESPAWN_T + RESPAWN_GAP * respawn.size
                    if (score[scorer] >= WIN_SCORE) winner = scorer
                }
                else -> {}
            }
        }
        world.events.clear()
        if (winner >= 0) {
            fx.add(Fx(FxKind.WIN, 0f, 0f, winner))
            return
        }
        val it = respawn.entries.iterator()
        while (it.hasNext()) {
            val en = it.next()
            en.setValue(en.value - dt)
            if (en.value <= 0f && en.key.state == BallState.GONE) {
                en.key.respawn()
                it.remove()
            }
        }
    }

    companion object {
        const val WIN_SCORE = 7
        const val SHOVE_COOLDOWN = 1.5f
        const val SHOVE_POWER = 120f
        const val RESPAWN_GAP = 0.6f
    }
}
