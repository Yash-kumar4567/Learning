package com.papertilt.game.core

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

enum class FxKind { BUMP, GOOD, BAD, WIN, LOSE, SPAWN, SHOVE, POINT }

/** Something the screen should react to (sound, floating text). [n] is points or a player index. */
class Fx(val kind: FxKind, val x: Float, val y: Float, val n: Int = 0)

const val MAX_OUTS = 3
private const val RESPAWN_T = 0.8f

/** Campaign level: put every ball in a hole of its size with fewer than three outs. */
class SoloGame(val level: Int) {
    private val built = Levels.build(level)
    val world: World = built.world
    val hint: String? = built.hint
    val fx = ArrayList<Fx>()
    var outs = 0
    var time = 0f
    var won = false
    var lost = false
    private val respawn = HashMap<Ball, Float>()

    val over: Boolean get() = won || lost
    val stars: Int get() = max(1, 3 - outs)

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
}

/** Endless mode: nine holes, balls keep arriving, three outs and it is over. */
class RushGame(seed: Long = System.nanoTime()) {
    val world = World(Levels.W, Levels.H, holesFill = false)
    val fx = ArrayList<Fx>()
    var score = 0
    var streak = 0
    var sunk = 0
    var outs = 0
    var over = false
    private val rng = Random(seed)
    private var spawnTimer = 0.6f
    private var nextId = 0
    private val spots = listOf(
        36f to 52.5f, 64f to 52.5f, 36f to 97.5f, 64f to 97.5f, 50f to 52.5f, 50f to 97.5f,
    )

    init {
        val sizes = (List(3) { Size.S } + List(3) { Size.M } + List(3) { Size.L }).shuffled(rng)
        var i = 0
        for (y in listOf(30f, 75f, 120f)) for (x in listOf(22f, 50f, 78f)) world.holes.add(Hole(x, y, sizes[i++]))
    }

    val multiplier: Int get() = min(5, streak + 1)

    fun step(dt: Float, tx: Float, ty: Float) {
        if (over) return
        world.step(dt, tx, ty)
        for (e in world.events) {
            when (e.type) {
                EvType.BUMP -> fx.add(Fx(FxKind.BUMP, e.ball.x, e.ball.y))
                EvType.SINK_OK -> {
                    val pts = 10 * multiplier
                    streak++
                    sunk++
                    score += pts
                    fx.add(Fx(FxKind.GOOD, e.ball.x, e.ball.y, pts))
                }
                EvType.SINK_BAD, EvType.FALL -> {
                    outs++
                    streak = 0
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
        spawnTimer -= dt
        if (world.balls.isEmpty()) spawnTimer = min(spawnTimer, 0.3f)
        val cap = min(6, 2 + sunk / 4)
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
                spawnTimer = max(1.3f, 3.4f - sunk * 0.05f)
            }
        }
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

    /** Push every marble away from [player]'s end; strongest right at their edge. */
    fun shove(player: Int) {
        if (winner >= 0 || cooldown[player] > 0f) return
        cooldown[player] = SHOVE_COOLDOWN
        for (b in world.balls) {
            if (b.state != BallState.ROLLING) continue
            val near = if (player == 0) b.y / world.h else 1f - b.y / world.h
            val kick = SHOVE_POWER * near * near
            b.vy += if (player == 0) -kick else kick
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
                EvType.FALL -> {
                    val scorer = if (e.ball.y < world.h / 2) 0 else 1
                    score[scorer]++
                    fx.add(Fx(FxKind.POINT, e.ball.x.coerceIn(10f, world.w - 10f), e.ball.y.coerceIn(10f, world.h - 10f), scorer))
                    respawn[e.ball] = RESPAWN_T
                }
                else -> {}
            }
        }
        world.events.clear()
        for (p in 0..1) if (score[p] >= WIN_SCORE && winner < 0) {
            winner = p
            fx.add(Fx(FxKind.WIN, 0f, 0f, p))
        }
        if (winner >= 0) return
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
        const val SHOVE_COOLDOWN = 2.5f
        const val SHOVE_POWER = 150f
    }
}
