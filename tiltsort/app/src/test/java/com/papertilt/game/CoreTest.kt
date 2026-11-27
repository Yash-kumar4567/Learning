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

    @Test fun shovePushesAwayFromThePlayerAndCoolsDown() {
        val g = TugGame()
        g.shove(0)
        assertTrue(g.world.balls.all { it.vy < 0f })
        assertTrue(g.cooldown[0] > 0f)
        val before = g.world.balls.map { it.vy }
        g.shove(0)
        assertEquals(before, g.world.balls.map { it.vy })
    }
}
