package com.papertilt.game

import com.papertilt.game.core.Hole
import com.papertilt.game.core.Levels
import com.papertilt.game.core.Size
import com.papertilt.game.core.Wall
import com.papertilt.game.core.World
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fast structural checks on the 40 campaign layouts. No bot here; see SolvabilityProbe for play-testing. */
class LevelsTest {

    private fun world(n: Int) = worlds[n - 1]

    private fun inner(w: World): List<Wall> = w.walls.drop(w.railed.count { it })
    private fun seg(wl: Wall) = floatArrayOf(wl.x1, wl.y1, wl.x2, wl.y2)
    private fun rails(w: World) = w.railed.count { it }

    private fun openEdgeDistance(w: World, x: Float, y: Float): Float {
        var e = Float.MAX_VALUE
        if (!w.railed[0]) e = min(e, y)
        if (!w.railed[1]) e = min(e, w.w - x)
        if (!w.railed[2]) e = min(e, w.h - y)
        if (!w.railed[3]) e = min(e, x)
        return e
    }

    private fun railedEdgeDistance(w: World, x: Float, y: Float): Float {
        var e = Float.MAX_VALUE
        if (w.railed[0]) e = min(e, y)
        if (w.railed[1]) e = min(e, w.w - x)
        if (w.railed[2]) e = min(e, w.h - y)
        if (w.railed[3]) e = min(e, x)
        return e
    }

    private fun signature(w: World): String =
        w.railed.joinToString("") { if (it) "1" else "0" } + "|" +
            w.balls.joinToString { "%s%.1f,%.1f".format(it.size, it.x, it.y) } + "|" +
            w.holes.joinToString { "%s%.1f,%.1f,%.1f,%.1f".format(it.size, it.baseX, it.baseY, it.ampX, it.ampY) } + "|" +
            inner(w).joinToString { "%.1f,%.1f,%.1f,%.1f".format(it.x1, it.y1, it.x2, it.y2) } + "|" +
            w.sand.joinToString { "%.1f,%.1f,%.1f,%.1f".format(it.x, it.y, it.w, it.h) }

    // ------------------------------------------------------------ A2: fair starts

    @Test fun spawnsAreClearOfOpenEdgesAndWrongHoles() {
        for (n in 1..Levels.COUNT) {
            val w = world(n)
            for (b in w.balls) {
                assertTrue("level $n ball ${b.id}: 20 from every open edge", openEdgeDistance(w, b.x, b.y) >= 20f)
                for ((hi, h) in w.holes.withIndex()) {
                    val d = Levels.trackDist(h, b.x, b.y)
                    if (h.size.ordinal > b.size.ordinal) {
                        assertTrue("level $n ball ${b.id}: clear of bigger hole $hi (%.1f)".format(d), d >= h.r + b.r + 12f)
                    }
                    if (h.moving) {
                        assertTrue("level $n ball ${b.id}: out of reach of moving hole $hi (%.1f)".format(d), d >= h.r + b.r + 12f)
                    }
                    assertTrue("level $n ball ${b.id}: not on the rim of hole $hi", d >= h.r + b.r + 5f)
                }
                for (o in w.balls) {
                    if (o !== b) assertTrue("level $n balls ${b.id} and ${o.id} apart", hypot(o.x - b.x, o.y - b.y) >= o.r + b.r + 3f)
                }
                assertTrue("level $n ball ${b.id} not in sand", w.sand.none { it.contains(b.x, b.y) })
            }
        }
    }

    // ------------------------------------------------------------ A5: clean layouts

    @Test fun pencilWallsKeepTheirDistanceOrAreJoined() {
        for (n in 1..Levels.COUNT) {
            val w = world(n)
            val walls = inner(w)
            for (i in walls.indices) {
                val a = walls[i]
                assertTrue("level $n wall $i has a length", hypot(a.x2 - a.x1, a.y2 - a.y1) >= 14f)
                for (j in i + 1 until walls.size) {
                    val b = walls[j]
                    val d = Levels.segSeg(seg(a), seg(b))
                    // joined on purpose: one shared end point and a right angle (an L)
                    val shared = (a.x1 == b.x1 && a.y1 == b.y1) || (a.x1 == b.x2 && a.y1 == b.y2) ||
                        (a.x2 == b.x1 && a.y2 == b.y1) || (a.x2 == b.x2 && a.y2 == b.y2)
                    val dot = (a.x2 - a.x1) * (b.x2 - b.x1) + (a.y2 - a.y1) * (b.y2 - b.y1)
                    val joined = shared && abs(dot) < 1e-3f
                    assertTrue("level $n walls $i and $j are %.1f apart".format(d), joined || d >= 14f)
                }
                // each end: clear of the railed edges, or sitting exactly on one
                for ((x, y) in listOf(a.x1 to a.y1, a.x2 to a.y2)) {
                    val onRail = (x == 0f && w.railed[3]) || (x == w.w && w.railed[1]) || (y == 0f && w.railed[0]) || (y == w.h && w.railed[2])
                    val d = railedEdgeDistance(w, x, y)
                    assertTrue("level $n wall $i end (%.1f, %.1f) is %.1f from a rail".format(x, y, d), onRail || d >= 14f)
                    assertTrue("level $n wall $i end on the paper", x in 0f..w.w && y in 0f..w.h)
                }
                // a wall attached to a rail leaves the neighbouring rails alone
                val attached = listOf(a.x1 to a.y1, a.x2 to a.y2).count { (x, y) -> x == 0f || x == w.w || y == 0f || y == w.h }
                assertTrue("level $n wall $i touches at most one edge", attached <= 1)
                for (h in w.holes) {
                    assertTrue("level $n wall $i clear of hole track", Levels.segSeg(seg(a), Levels.track(h)) >= h.r + 5f)
                }
                for (b in w.balls) {
                    assertTrue("level $n wall $i clear of spawn ${b.id}", Levels.segDist(b.x, b.y, a.x1, a.y1, a.x2, a.y2) >= b.r + 4f)
                }
            }
        }
    }

    @Test fun holesAreApartAndOnThePaper() {
        for (n in 1..Levels.COUNT) {
            val w = world(n)
            for ((i, a) in w.holes.withIndex()) {
                for (j in i + 1 until w.holes.size) {
                    val b = w.holes[j]
                    val d = Levels.segSeg(Levels.track(a), Levels.track(b)) - a.r - b.r
                    assertTrue("level $n holes $i and $j rims %.1f apart".format(d), d >= 9f)
                }
                val t = Levels.track(a)
                for ((x, y) in listOf(t[0] to t[1], t[2] to t[3])) {
                    assertTrue("level $n hole $i on the paper", x >= 13f && x <= w.w - 13f && y >= 13f && y <= w.h - 13f)
                }
            }
        }
    }

    // ------------------------------------------------------------ counts and the built-in solution

    @Test fun everyGeneratedLevelHasWhatItsPlanAsksFor() {
        for (n in 5..Levels.COUNT) {
            val w = world(n)
            val sp = Levels.spec(n)
            assertEquals("level $n rails", sp.rails, rails(w))
            assertEquals("level $n balls", sp.balls, w.balls.size)
            assertEquals("level $n holes", sp.holes, w.holes.size)
            assertEquals("level $n moving holes", sp.moving, w.holes.count { it.moving })
            assertEquals("level $n sand", sp.sand, w.sand.size)
            assertEquals("level $n walls", sp.walls, inner(w).size)
            for (s in Size.values()) {
                assertTrue("level $n: a hole for every $s ball", w.holes.count { it.size == s } >= w.balls.count { it.size == s })
            }
            assertTrue("level $n has a wrong hole to mind", w.balls.any { b -> w.holes.any { it.size.ordinal > b.size.ordinal } })
            assertEquals("level $n ball ids", (0 until sp.balls).toList(), w.balls.map { it.id })
        }
    }

    @Test fun thePickedLayoutsAreAcceptableAndBuildFast() {
        // the generator skips a layout that fails its checks; the table must not rely on that
        for (n in 5..Levels.COUNT) assertTrue("level $n: the picked layout passes every check", Levels.pickIsAcceptable(n))
        val t0 = System.nanoTime()
        for (n in 1..Levels.COUNT) Levels.build(n)
        val ms = (System.nanoTime() - t0) / 1e6
        assertTrue("building all 40 levels took $ms ms", ms < 1500.0)
    }

    @Test fun everyGeneratedLevelHasASlideOnlySolutionWithRoom() {
        for (n in 5..Levels.COUNT) {
            val w = world(n)
            val sp = Levels.spec(n)
            assertTrue("level $n keeps a sane margin", sp.edge >= 12f && sp.keep >= 5f)
            val solver = Levels.Solver(w, inner(w), sp.edge, sp.keep)
            assertTrue("level $n: rolling any ball straight at its nearest hole does not lose another at once", solver.openingsAreSafe())
            val starts = solver.starts(sp.starts)
            assertTrue("level $n: $starts of ${sp.starts} opening moves", starts >= sp.starts)
        }
    }

    // ------------------------------------------------------------ A4: the knob schedule

    @Test fun oneNewThingAtATime() {
        var sandSeen = false
        var movingSeen = false
        for (n in 2..Levels.COUNT) {
            val a = world(n - 1)
            val b = world(n)
            val changes = ArrayList<String>()
            if (rails(a) != rails(b)) changes.add("rails ${rails(a)}->${rails(b)}")
            if (a.balls.size != b.balls.size) changes.add("balls ${a.balls.size}->${b.balls.size}")
            if (b.sand.isNotEmpty() && !sandSeen) { sandSeen = true; changes.add("first sand") }
            if (b.holes.any { it.moving } && !movingSeen) { movingSeen = true; changes.add("first moving hole") }
            if (a.holes.count { it.moving } != b.holes.count { it.moving } && "first moving hole" !in changes) changes.add("moving holes")
            if (inner(b).isNotEmpty() && (1 until n).none { inner(world(it)).isNotEmpty() }) changes.add("first wall")
            // the hand-made tutorials each teach one rule with their own tiny layout; the rule applies from level 5 on
            if (n >= 5) assertTrue("level $n changes $changes at once", changes.size <= 1)
            if (n >= 5) {
                assertTrue("level $n: ball count never drops", b.balls.size >= a.balls.size)
                assertTrue("level $n: ball count rises by one", b.balls.size <= a.balls.size + 1)
                assertTrue("level $n: moving holes never drop", b.holes.count { it.moving } >= a.holes.count { it.moving })
                assertTrue("level $n: moving holes rise by one", b.holes.count { it.moving } <= a.holes.count { it.moving } + 1)
            }
        }
    }

    @Test fun railsComeOffGraduallyThenMix() {
        val r = (1..Levels.COUNT).map { rails(world(it)) }
        val firstZero = r.indexOf(0) + 1
        assertEquals(Levels.firstNoRails, firstZero)
        // before the first no-rail level the count only goes down, one rail at a time, from level 5 on
        for (n in 6 until firstZero) assertTrue("level $n rails", r[n - 1] == r[n - 2] || r[n - 1] == r[n - 2] - 1)
        assertEquals("the first no-rail level follows a one-rail level", 1, r[firstZero - 2])
        // afterwards 0, 1 and 2 rails all appear, and never more than two no-rail levels in a row
        val late = r.subList(firstZero - 1, Levels.COUNT)
        assertEquals(setOf(0, 1, 2), late.toSet())
        assertTrue("at least four of each kind late on", (0..2).all { k -> late.count { it == k } >= 4 })
        for (i in 2 until late.size) assertTrue("three no-rail levels in a row at ${firstZero + i}", !(late[i] == 0 && late[i - 1] == 0 && late[i - 2] == 0))
        assertEquals("the last level has no rails", 0, r[Levels.COUNT - 1])
        // level 4: the pencil-wall lesson, with a top and a left rail
        assertTrue(world(4).railed.contentEquals(booleanArrayOf(true, false, false, true)))
        assertEquals(2, inner(world(4)).size)
    }

    // ------------------------------------------------------------ A7: hints

    @Test fun hintsSitOnTheLevelThatIntroducesTheFeature() {
        val firstSand = (1..Levels.COUNT).first { world(it).sand.isNotEmpty() }
        val firstMoving = (1..Levels.COUNT).first { n -> world(n).holes.any { it.moving } }
        val firstNoRails = (1..Levels.COUNT).first { rails(world(it)) == 0 }
        val expected = mapOf(
            1 to "Tilt your phone to roll\nthe ball into the hole.",
            2 to "Match the size! A small ball lost\nin a big hole is an OUT.",
            3 to "No rail on a dashed edge.\nRoll off the paper and you're OUT.",
            4 to "Pencil walls stop a ball.\nLean on them.",
            5 to "All balls roll together.\nPlan the order.",
            firstSand to "Sand slows everything down.",
            firstMoving to "Some holes like to wander.",
            firstNoRails to "No rails at all now. Easy does it.",
        )
        assertEquals(8, expected.size)
        for (n in 1..Levels.COUNT) {
            val hint = Levels.build(n).hint
            if (n in expected) assertEquals("level $n hint", expected[n], hint) else assertNull("level $n has no hint", hint)
        }
    }

    // ------------------------------------------------------------ A6: pars

    @Test fun parsAreWholeSecondsInASaneRange() {
        var prevBand = 0f
        for (n in 1..Levels.COUNT) {
            val built = Levels.build(n)
            val par = built.par
            assertEquals("level $n par is a whole number of seconds", par, Math.round(par).toFloat(), 0f)
            assertTrue("level $n par $par in range", par >= 4f && par <= 30f)
            // nobody can be asked to sink a ball a second from a standing start
            assertTrue("level $n par $par leaves time per ball", par >= 1f + built.world.balls.size)
        }
        // the late game takes longer than the early game
        for (band in listOf(1..10, 11..20, 21..30, 31..40)) {
            val avg = band.map { Levels.build(it).par }.average().toFloat()
            assertTrue("par rises band by band ($band: $avg after $prevBand)", avg > prevBand)
            prevBand = avg
        }
    }

    // ------------------------------------------------------------ A8: determinism, distinct layouts, tutorials untouched

    @Test fun layoutsAreRepeatableAndAllDifferent() {
        val sigs = (1..Levels.COUNT).map { signature(world(it)) }
        for (n in 1..Levels.COUNT) assertEquals("level $n builds the same twice", sigs[n - 1], signature(Levels.build(n).world))
        assertEquals("all layouts differ", Levels.COUNT, sigs.toSet().size)
        // no two levels share ball spawns or hole positions either
        assertEquals(Levels.COUNT, worlds.map { w -> w.balls.map { it.x to it.y } }.toSet().size)
        assertEquals(Levels.COUNT, worlds.map { w -> w.holes.map { it.baseX to it.baseY } }.toSet().size)
        assertEquals(40, Levels.COUNT)
        assertEquals(100f, Levels.W, 0f)
        assertEquals(150f, Levels.H, 0f)
    }

    @Test fun everyLevelIsHarderThanTheOneBefore() {
        var prev = Levels.build(1)
        assertEquals(1f, prev.world.push, 0f)
        assertEquals(1f, prev.world.grip, 0f)
        assertEquals(3, prev.outs)
        for (n in 2..Levels.COUNT) {
            val b = Levels.build(n)
            assertTrue("level $n pushes harder", b.world.push > prev.world.push)
            assertTrue("level $n is more slippery", b.world.grip < prev.world.grip)
            assertTrue("level $n never forgives more outs", b.outs <= prev.outs)
            assertTrue("level $n leaves less slack over par", b.limit / b.par < prev.limit / prev.par + 0.2f)
            assertTrue("level $n: the clock allows at least half as long again as par", b.limit >= b.par * 1.5f)
            prev = b
        }
        assertEquals(1, prev.outs)
        assertTrue(prev.world.grip >= 0.45f)
    }

    @Test fun everyLevelHasExactlyOneHolePerBall() {
        for (n in 1..Levels.COUNT) {
            val w = world(n)
            assertEquals("level $n", w.balls.map { it.size }.sortedBy { it.ordinal }, w.holes.map { it.size }.sortedBy { it.ordinal })
        }
    }

    @Test fun theFirstThreeTutorialsAreAsDesigned() {
        fun balls(w: World) = w.balls.map { "${it.size}@${it.x},${it.y}" }
        fun holes(w: World) = w.holes.map { h: Hole -> "${h.size}@${h.baseX},${h.baseY}" + if (h.moving) "~" else "" }
        val one = world(1)
        assertTrue(one.railed.all { it })
        assertEquals(listOf("M@50.0,115.0"), balls(one))
        assertEquals(listOf("M@50.0,40.0"), holes(one))
        val two = world(2)
        assertTrue(two.railed.all { it })
        assertEquals(listOf("S@30.0,120.0", "L@70.0,120.0"), balls(two))
        assertEquals(listOf("L@30.0,40.0", "S@70.0,40.0"), holes(two))
        val three = world(3)
        assertTrue(three.railed.contentEquals(booleanArrayOf(false, true, false, true)))
        assertEquals(listOf("M@50.0,118.0"), balls(three))
        assertEquals(listOf("M@50.0,32.0"), holes(three))
        for (n in 1..3) {
            assertEquals(0, inner(world(n)).size)
            assertEquals(0, world(n).sand.size)
        }
    }

    companion object {
        // built once for the whole class: JUnit makes a new instance for every test
        private val worlds: List<World> by lazy { (1..Levels.COUNT).map { Levels.build(it).world } }
    }
}
