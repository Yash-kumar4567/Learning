package com.papertilt.game

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Draws every screen off-device and saves PNGs to build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreenshotTest {
    private val w = 720
    private val h = 1560

    private fun shot(view: GameView, name: String) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun everyScreenDraws() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val v = activity.view
        shot(v, "01_menu")
        v.simulate(1.5f, 0.2f, 0.1f)
        shot(v, "01_menu")
        v.open(GameView.Screen.LEVELS)
        shot(v, "02_levels")
        v.open(GameView.Screen.HOWTO)
        shot(v, "03_howto")
        v.startSolo(2)
        v.simulate(0.8f, 0.1f, -0.15f)
        shot(v, "04_level2")
        v.startSolo(1)
        v.simulate(8f, 0f, -0.2f)
        shot(v, "05_level1_won")
        v.startSolo(3)
        v.simulate(30f, 0f, 0.4f)
        shot(v, "06_level3_lost")
        v.startSolo(18)
        v.simulate(0.6f, 0f, 0f)
        shot(v, "07_level18")
        v.startSolo(33)
        v.simulate(0.6f, 0f, 0f)
        shot(v, "08_level33")
        v.startRush()
        v.simulate(5f, 0.03f, 0.02f)
        shot(v, "09_rush")
        v.startTug()
        v.simulate(1.2f, 0.1f, -0.2f)
        shot(v, "10_tug")
        v.simulate(120f, 0f, -0.4f)
        shot(v, "11_tug_won")
        v.startSolo(5)
        v.onBack()
        shot(v, "12_paused")
        assertEquals(GameView.Screen.SOLO, v.screen)
    }
}
