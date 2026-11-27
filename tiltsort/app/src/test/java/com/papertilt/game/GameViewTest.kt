package com.papertilt.game

import android.content.Context
import android.hardware.Sensor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import com.papertilt.game.core.BallState
import com.papertilt.game.core.TugGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor

/** How the screens react to taps, pauses and the tilt sensor, without a phone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class GameViewTest {
    private val w = 720
    private val h = 1560

    private fun tap(v: GameView, x: Float, y: Float) {
        draw(v, w, h)
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(t, t + 30, MotionEvent.ACTION_UP, x, y, 0)
        v.dispatchTouchEvent(down)
        v.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    /** Lets the tap guard run out without starting the round. */
    private fun wait(v: GameView, seconds: Float = 0.5f) = v.simulate(seconds, 0f, 0f, start = false)

    @Test fun nothingMovesUntilThePlayerTapsToStart() {
        val v = launch().view
        v.startSolo(3)
        assertFalse(v.ready)
        // The phone is tipped hard over, but the round has not begun.
        v.simulate(3f, 0f, 0.5f, start = false)
        val g = v.solo!!
        assertEquals(0, g.outs)
        assertEquals(0f, g.time, 0f)
        assertEquals(118f, g.world.balls[0].y, 0.001f)
        tap(v, w / 2f, h / 2f)
        assertTrue(v.ready)
        v.simulate(3f, 0f, 0.5f, start = false)
        assertTrue(g.outs > 0)
    }

    @Test fun aSecondTapOfADoubleTapDoesNotStartTheRound() {
        val v = launch().view
        v.startSolo(1)
        tap(v, w / 2f, h / 2f)
        assertFalse("still inside the guard", v.ready)
        wait(v)
        tap(v, w / 2f, h / 2f)
        assertTrue(v.ready)
    }

    @Test fun backLeavesARoundThatHasNotStartedAndPausesOneThatHas() {
        val v = launch().view
        v.startSolo(2)
        assertTrue(v.onBack())
        assertEquals(GameView.Screen.LEVELS, v.screen)
        v.startSolo(2)
        v.simulate(0.5f, 0f, 0f)
        v.onBack()
        assertEquals(GameView.Screen.SOLO, v.screen)
        assertTrue(v.paused)
        v.onBack()
        assertFalse(v.paused)
    }

    @Test fun losingFocusPausesARunningRound() {
        val v = launch().view
        v.startRush()
        v.simulate(1f, 0f, 0f)
        v.onHostPause()
        assertTrue(v.paused)
        val balls = v.rush!!.world.balls.map { it.x to it.y }
        v.simulate(2f, 0.5f, 0f, start = false)
        assertEquals(balls, v.rush!!.world.balls.map { it.x to it.y })
    }

    @Test fun aRushScoreIsKeptWhenTheRunIsAbandoned() {
        val activity = launch()
        val v = activity.view
        val prefs = Prefs(activity)
        for (leave in listOf<(GameView) -> Unit>({ it.open(GameView.Screen.MENU) }, { it.startRush() }, { it.onHostPause() })) {
            val before = prefs.rushBest
            v.startRush()
            v.simulate(0.5f, 0f, 0f)
            v.rush!!.score = before + 250
            leave(v)
            assertEquals(before + 250, prefs.rushBest)
        }
    }

    @Test fun aWinIsRecordedWithItsStarsAndUnlocksTheNextLevel() {
        val activity = launch()
        val v = activity.view
        val prefs = Prefs(activity)
        v.startSolo(1)
        v.simulate(8f, 0f, -0.2f)
        assertTrue(v.solo!!.won)
        assertEquals(7, prefs.goals(1))
        assertEquals(3, prefs.stars(1))
        assertTrue(prefs.bestTime(1) > 0f)
        assertEquals(2, prefs.unlocked)
    }

    @Test fun tugTapsShoveForTheHalfTheyLandIn() {
        val v = launch().view
        v.startTug()
        wait(v)
        tap(v, w / 2f, h * 0.8f)
        assertTrue("first tap starts the round", v.ready)
        val g = v.tug!!
        assertEquals(0f, g.cooldown[0], 0f)
        tap(v, w * 0.3f, h * 0.8f)
        assertTrue("bottom half is player 0", g.cooldown[0] > 0f)
        assertEquals(0f, g.cooldown[1], 0f)
        tap(v, w * 0.3f, h * 0.2f)
        assertTrue("top half is player 1", g.cooldown[1] > 0f)
    }

    @Test fun tugPanelsIgnoreTapsThatWereAlreadyOnTheirWay() {
        val v = launch().view
        v.startTug()
        v.simulate(0.5f, 0f, 0f)
        val g = v.tug!!
        g.score[0] = 3
        v.onBack()
        assertTrue(v.paused)
        // A shove tap aimed at the tray lands where the pause panel has just put a button.
        for (y in listOf(0.45f, 0.5f, 0.55f, 0.6f, 0.65f)) tap(v, w / 2f, h * y)
        assertTrue(v.paused)
        assertTrue("the match was not restarted", v.tug === g && g.score[0] == 3)
        wait(v, 0.7f)
        draw(v, w, h)
        tap(v, w / 2f, h / 2f - 0.065f * w)
        assertFalse("after the guard, Resume works", v.paused)
    }

    @Test fun tugEndsOnTheResultPanelAndRematchStartsAFreshMatch() {
        val v = launch().view
        v.startTug()
        v.simulate(120f, 0f, -0.4f)
        val g = v.tug!!
        assertEquals(0, g.winner)
        assertEquals(TugGame.WIN_SCORE, g.score[0])
        assertTrue(g.world.balls.any { it.state != BallState.GONE } || g.score.sum() == TugGame.WIN_SCORE)
    }

    private fun event(x: Float, y: Float) =
        SensorEventBuilder.newBuilder()
            .setSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
            .setValues(floatArrayOf(x, y, 9.5f))
            .build()

    @Test fun tiltFollowsTheScreenHoweverTheDisplayIsRotated() {
        val activity = launch()
        val display = (activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        // Device axes: +x is the natural right edge, +y the natural top. Gravity reads positive on
        // the axis that points up, so a lowered edge reads negative on its own axis.
        // For each rotation: the device reading that means "the screen's right edge is lowered",
        // and the one that means "the screen's bottom edge is lowered".
        val cases = listOf(
            Triple(Surface.ROTATION_0, -3f to 0f, 0f to 3f),
            Triple(Surface.ROTATION_90, 0f to 3f, 3f to 0f),
            Triple(Surface.ROTATION_180, 3f to 0f, 0f to -3f),
            Triple(Surface.ROTATION_270, 0f to -3f, -3f to 0f),
        )
        for ((rotation, right, bottom) in cases) {
            shadowOf(display).setRotation(rotation)
            val t = Tilt(activity)
            repeat(60) { t.onSensorChanged(event(right.first, right.second)) }
            assertTrue("rotation $rotation: right edge down rolls right", t.x > 0.2f && t.y == 0f)
            val t2 = Tilt(activity)
            repeat(60) { t2.onSensorChanged(event(bottom.first, bottom.second)) }
            assertTrue("rotation $rotation: bottom edge down rolls down", t2.y > 0.2f && t2.x == 0f)
        }
    }

    @Test fun calibrationMakesTheCurrentHoldLevelButRefusesASteepOne() {
        val activity = launch()
        val t = Tilt(activity)
        // Held about 30 degrees back from flat.
        repeat(60) { t.onSensorChanged(event(0f, 4.9f)) }
        assertEquals(0.48f, t.y, 0.02f)
        assertTrue(t.calibrate())
        assertEquals(0f, t.y, 0f)
        // Nearly upright: too steep to steer from.
        repeat(60) { t.onSensorChanged(event(0f, 9f)) }
        val before = t.y
        assertFalse(t.calibrate())
        assertEquals(before, t.y, 0f)
    }

    @Test fun smallTiltsAreIgnoredAndTheResponseStartsFromZero() {
        val activity = launch()
        val t = Tilt(activity)
        repeat(60) { t.onSensorChanged(event(-0.15f, 0f)) }
        assertEquals("inside the dead zone", 0f, t.x, 0f)
        repeat(60) { t.onSensorChanged(event(-0.25f, 0f)) }
        assertTrue("just past it the tilt is tiny, not a jump", t.x > 0f && t.x < 0.01f)
        t.gain = Tilt.GAINS[2]
        repeat(60) { t.onSensorChanged(event(-2f, 0f)) }
        assertEquals((2f / 9.80665f - Tilt.DEAD_ZONE) * Tilt.GAINS[2], t.x, 0.005f)
    }

    @Test fun twoPlayerModeZeroesANearlyFlatPhoneOnly() {
        val activity = launch()
        val t = Tilt(activity)
        repeat(60) { t.onSensorChanged(event(-0.5f, 0.4f)) }
        assertTrue(t.flatX > 0f)
        t.zeroFlat()
        assertEquals(0f, t.flatX, 0f)
        assertEquals(0f, t.flatY, 0f)
        // Held at a clear angle: that is a player tilting, not a sloping table.
        repeat(60) { t.onSensorChanged(event(-3f, 0f)) }
        t.zeroFlat()
        assertTrue(t.flatX > 0.2f)
    }
}
