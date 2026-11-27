package com.papertilt.game

import com.papertilt.game.core.Ball
import com.papertilt.game.core.BallState
import com.papertilt.game.core.EvType
import com.papertilt.game.core.Hole
import com.papertilt.game.core.Levels
import com.papertilt.game.core.RushGame
import com.papertilt.game.core.Size
import com.papertilt.game.core.SoloGame
import com.papertilt.game.core.TugGame
import com.papertilt.game.core.World
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    private val dt = 1f / 120f

    private fun run(w: World, seconds: Float, tx: Float, ty: Float): List<EvType> {
        val seen = ArrayList<EvType>()
        var t = 0f
        while (t < seconds) {
            w.step(dt, tx, ty)
            seen.addAll(w.events.map { it.type })
            w.events.clear()
            t += dt
        }
        return seen
    }

    @Test fun ballRollsIntoMatchingHoleAndStays() {
        val w = World(100f, 150f)
        w.holes.add(Hole(50f, 40f, Size.M))
        w.balls.add(Ball(50f, 110f, Size.M, 0))
        val ev = run(w, 4f, 0f, -0.2f)
        assertTrue(EvType.SINK_OK in ev)
        assertEquals(BallState.HOME, w.balls[0].state)
        assertTrue(w.holes[0].filled)
    }

    @Test fun smallBallInBigHoleIsBad() {
        val w = World(100f, 150f)
        w.holes.add(Hole(50f, 40f, Size.L))
        w.balls.add(Ball(50f, 110f, Size.S, 0))
        val ev = run(w, 4f, 0f, -0.2f)
        assertTrue(EvType.SINK_BAD in ev)
        assertEquals(BallState.GONE, w.balls[0].state)
        assertFalse(w.holes[0].filled)
    }

    @Test fun bigBallRollsOverSmallHoleAndFallsOffOpenEdge() {
        val w = World(100f, 150f)
        w.holes.add(Hole(50f, 40f, Size.S))
        w.balls.add(Ball(50f, 110f, Size.L, 0))
        val ev = run(w, 5f, 0f, -0.2f)
        assertFalse(EvType.SINK_OK in ev || EvType.SINK_BAD in ev)
        assertTrue(EvType.FALL in ev)
    }

    @Test fun railsKeepBallsOnTheTray() {
        val w = World(100f, 150f)
        w.rails(true, true, true, true)
        w.balls.add(Ball(50f, 75f, Size.S, 0))
        w.balls.add(Ball(60f, 75f, Size.L, 1))
        for ((tx, ty) in listOf(0.5f to 0.5f, -0.5f to 0.5f, 0.5f to -0.5f, -0.5f to -0.5f, 0f to 0.5f)) {
            val ev = run(w, 3f, tx, ty)
            assertFalse(EvType.FALL in ev)
        }
        for (b in w.balls) assertTrue(b.x > 0f && b.x < 100f && b.y > 0f && b.y < 150f)
    }

    @Test fun filledHoleLetsOthersPass() {
        val w = World(100f, 150f)
        w.holes.add(Hole(50f, 75f, Size.L))
        w.balls.add(Ball(50f, 100f, Size.L, 0))
        run(w, 3f, 0f, -0.2f)
        assertEquals(BallState.HOME, w.balls[0].state)
        w.balls.add(Ball(50f, 110f, Size.S, 1))
        val ev = run(w, 5f, 0f, -0.2f)
        assertFalse(EvType.SINK_BAD in ev)
        assertTrue(EvType.FALL in ev)
    }

    @Test fun everyLevelIsWellFormed() {
        for (n in 1..Levels.COUNT) {
            val w = Levels.build(n).world
            assertTrue("level $n has balls", w.balls.isNotEmpty())
            for (s in Size.values()) {
                assertTrue(
                    "level $n: a hole for every $s ball",
                    w.holes.count { it.size == s } >= w.balls.count { it.size == s },
                )
            }
            for (b in w.balls) {
                assertTrue("level $n ball on tray", b.x > b.r && b.x < w.w - b.r && b.y > b.r && b.y < w.h - b.r)
                for (h in w.holes) assertTrue("level $n ball clear of hole", hypot(b.x - h.x, b.y - h.y) > h.r + b.r)
            }
            for (h in w.holes) {
                for (k in listOf(-1f, 1f)) {
                    val x = h.baseX + h.ampX * k
                    val y = h.baseY + h.ampY * k
                    assertTrue("level $n hole on tray", x > h.r && x < w.w - h.r && y > h.r && y < w.h - h.r)
                }
            }
            // Nothing may go wrong while the phone lies flat.
            val g = SoloGame(n)
            repeat(600) { g.step(dt, 0f, 0f) }
            assertEquals("level $n is calm when flat", 0, g.outs)
        }
    }

    @Test fun levelsAreTheSameEveryTime() {
        val a = Levels.build(17).world
        val b = Levels.build(17).world
        assertEquals(a.balls.map { it.x to it.y }, b.balls.map { it.x to it.y })
        assertEquals(a.holes.map { it.baseX to it.baseY }, b.holes.map { it.baseX to it.baseY })
    }

    @Test fun levelOneCanBeWonAndLevelThreeCanBeLost() {
        val g = SoloGame(1)
        repeat(1200) { g.step(dt, 0f, -0.2f) }
        assertTrue(g.won)
        assertEquals(3, g.stars)
        assertTrue(g.time < g.par)

        val l = SoloGame(3)
        repeat(6000) { l.step(dt, 0f, 0.4f) }
        assertTrue(l.lost)
        assertEquals(3, l.outs)
    }

    /** A crude player: tilt towards the nearest right-sized hole of the first loose ball. */
    @Test fun aSimpleBotClearsTheTutorials() {
        for (n in listOf(1, 3)) {
            val g = SoloGame(n)
            var steps = 0
            while (!g.over && steps < 120 * 60) {
                val b = g.world.balls.firstOrNull { it.state == BallState.ROLLING }
                var tx = 0f
                var ty = 0f
                if (b != null) {
                    val h = g.world.holes.first { !it.filled && it.size == b.size }
                    val dx = h.x - b.x
                    val dy = h.y - b.y
                    val d = hypot(dx, dy)
                    tx = (dx / d * 0.12f - b.vx * 0.004f).coerceIn(-0.5f, 0.5f)
                    ty = (dy / d * 0.12f - b.vy * 0.004f).coerceIn(-0.5f, 0.5f)
                }
                g.step(dt, tx, ty)
                steps++
            }
            assertTrue("bot wins level $n", g.won)
        }
    }

    @Test fun aWinEarnsOneStarPerGoal() {
        val clean = SoloGame(1)
        repeat(1200) { clean.step(dt, 0f, -0.2f) }
        assertTrue(clean.won)
        assertEquals(SoloGame.GOAL_SORTED or SoloGame.GOAL_CLEAN or SoloGame.GOAL_FAST, clean.goals)

        // Dawdling past par costs the speed star and nothing else.
        val slow = SoloGame(1)
        repeat((slow.par * 120).toInt() + 120) { slow.step(dt, 0f, 0f) }
        repeat(1200) { slow.step(dt, 0f, -0.2f) }
        assertTrue(slow.won)
        assertEquals(SoloGame.GOAL_SORTED or SoloGame.GOAL_CLEAN, slow.goals)
        assertEquals(2, slow.stars)

        val unfinished = SoloGame(1)
        assertEquals(0, unfinished.stars)
    }

    @Test fun aBallWaitsToRespawnUntilItsSpotIsClear() {
        val w = World(100f, 150f)
        w.rails(true, true, true, true)
        val waiting = Ball(50f, 75f, Size.L, 0)
        waiting.respawn()
        w.balls.add(waiting)
        val squatter = Ball(52f, 75f, Size.S, 1)
        w.balls.add(squatter)
        run(w, 1f, 0f, 0f)
        assertEquals(BallState.SPAWNING, waiting.state)
        assertEquals(52f, squatter.x, 0.001f)
        // Tilt the squatter away and the ball comes in.
        run(w, 2f, 0.3f, 0f)
        assertEquals(BallState.ROLLING, waiting.state)
    }

    @Test fun ballsNearAnOpenEdgeOrABiggerHoleAreInDanger() {
        val w = World(100f, 150f)
        w.rails(true, true, false, true)
        w.holes.add(Hole(50f, 40f, Size.L))
        w.holes.add(Hole(20f, 40f, Size.S))
        assertFalse(w.inDanger(Ball(50f, 75f, Size.S, 0)))
        assertTrue("open bottom edge", w.inDanger(Ball(50f, 145f, Size.S, 1)))
        assertFalse("railed top edge", w.inDanger(Ball(80f, 5f, Size.S, 2)))
        assertTrue("bigger hole", w.inDanger(Ball(50f, 50f, Size.S, 3)))
        assertFalse("own hole", w.inDanger(Ball(20f, 46f, Size.S, 4)))
        assertFalse("a big ball rolls over a small hole", w.inDanger(Ball(20f, 46f, Size.L, 5)))
    }

    @Test fun rushSpawnsScoresAndEnds() {
        val g = RushGame(seed = 42)
        assertEquals(9, g.world.holes.size)
        repeat(240) { g.step(dt, 0f, 0f) }
        assertTrue(g.world.balls.isNotEmpty())
        assertEquals(0, g.outs)
        // Holding the phone tipped hard over dumps every ball off the edge.
        repeat(120 * 60) { g.step(dt, 0.5f, 0f) }
        assertTrue(g.over)
    }

    /** Drops a ball of the hole's own size (or, for an out, a small ball into a large hole) and lets it sink. */
    private fun sink(g: RushGame, wrong: Boolean = false) {
        val h = if (wrong) g.world.holes.first { it.size == Size.L } else g.world.holes[g.sunk % 9]
        val b = Ball(h.x, h.y, if (wrong) Size.S else h.size, 1000 + g.sunk + g.outs * 100)
        g.world.balls.add(b)
        repeat(60) { g.step(dt, 0f, 0f) }
    }

    @Test fun rushChainGrowsDrainsAndBreaks() {
        val g = RushGame(seed = 7)
        assertEquals(1, g.multiplier)
        sink(g)
        assertEquals(10, g.score)
        assertEquals(2, g.multiplier)
        repeat(6) { sink(g) }
        assertEquals("chain is capped", 5, g.multiplier)
        // Waiting lets the chain run down one link at a time.
        val window = g.chainWindow
        g.world.balls.clear()
        var waited = 0f
        while (g.multiplier == 5 && waited < 20f) {
            g.world.balls.clear()
            g.step(dt, 0f, 0f)
            waited += dt
        }
        assertEquals(4, g.multiplier)
        assertTrue("a link lasts about one window", waited <= window + 0.1f && waited > window - 0.6f)
        // An out breaks it.
        sink(g, wrong = true)
        assertEquals(1, g.outs)
        assertEquals(1, g.multiplier)
    }

    @Test fun rushTenInARowErasesAnOut() {
        val g = RushGame(seed = 7)
        sink(g, wrong = true)
        assertEquals(1, g.outs)
        repeat(RushGame.MEND_EVERY - 1) { sink(g) }
        assertEquals(1, g.outs)
        sink(g)
        assertEquals(0, g.outs)
        // With no out to erase, a streak changes nothing.
        repeat(RushGame.MEND_EVERY) { sink(g) }
        assertEquals(0, g.outs)
    }

    @Test fun tugScoresForTheRightPlayerAndFinishes() {
        val g = TugGame()
        // Top edge down: marbles leave through player 1's end, so player 0 scores.
        var steps = 0
        while (g.winner < 0 && steps < 120 * 120) {
            g.step(dt, 0f, -0.4f)
            steps++
        }
        assertEquals(0, g.winner)
        assertEquals(TugGame.WIN_SCORE, g.score[0])
        assertEquals(0, g.score[1])
    }

    @Test fun tugNeverScoresPastTheWinningPoint() {
        for (ty in listOf(0.5f, -0.5f, 0.35f, -0.2f)) {
            val g = TugGame()
            var steps = 0
            while (g.winner < 0 && steps < 120 * 240) {
                g.step(dt, 0f, ty)
                steps++
            }
            assertTrue("someone wins at tilt $ty", g.winner >= 0)
            assertEquals(TugGame.WIN_SCORE, g.score[g.winner])
            repeat(600) { g.step(dt, 0f, ty) }
            assertEquals(TugGame.WIN_SCORE, g.score.sum())
        }
    }

    @Test fun shovePushesAwayFromThePlayerAndCoolsDown() {
        val g = TugGame()
        g.shove(0)
        assertTrue(g.world.balls.all { it.vy < 0f })
        assertTrue(g.cooldown[0] > 0f)
        val before = g.world.balls.map { it.vy }
        g.shove(0)
        assertEquals(before, g.world.balls.map { it.vy })
    }

    @Test fun shoveStopsAMarbleAtYourOwnEdge() {
        val g = TugGame()
        val b = g.world.balls[0]
        b.y = 140f
        b.vy = 90f
        g.shove(0)
        assertTrue("a marble about to leave player 0's end is turned round", b.vy < 0f)
        // The same marble is far from player 1, whose shove barely moves it.
        val h = TugGame()
        val far = h.world.balls[0]
        far.y = 140f
        h.shove(1)
        assertTrue(far.vy > 0f && far.vy < TugGame.SHOVE_POWER)
    }
}
