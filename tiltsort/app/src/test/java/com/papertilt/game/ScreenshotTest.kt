package com.papertilt.game

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.Sensor
import android.hardware.SensorManager
import android.view.View
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSensor

/** Builds the activity the way a phone would: with an accelerometer, unless told otherwise. */
fun launch(withSensor: Boolean = true): MainActivity {
    val controller = Robolectric.buildActivity(MainActivity::class.java)
    if (withSensor) {
        val manager = (controller.get() as Activity).getSystemService(Context.SENSOR_SERVICE) as SensorManager
        shadowOf(manager).addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
    }
    return controller.setup().get()
}

/** Lays the view out at the given size and draws one frame into a bitmap. */
fun draw(view: View, w: Int, h: Int): Bitmap {
    view.measure(
        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
    )
    view.layout(0, 0, w, h)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    view.draw(Canvas(bmp))
    return bmp
}

/** Draws every screen off-device and saves PNGs to build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreenshotTest {
    private fun shot(view: GameView, name: String, w: Int = 720, h: Int = 1560) {
        val bmp = draw(view, w, h)
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun everyScreenDraws() {
        val v = launch().view
        shot(v, "01_menu")
        v.simulate(1.5f, 0.2f, 0.1f)
        shot(v, "01_menu")
        v.open(GameView.Screen.LEVELS)
        shot(v, "02_levels")
        v.open(GameView.Screen.HOWTO)
        shot(v, "03_howto")
        v.startSolo(2)
        shot(v, "04_level2_tap_to_start")
        v.simulate(0.8f, 0.1f, -0.15f)
        shot(v, "05_level2")
        v.startSolo(1)
        v.simulate(8f, 0f, -0.2f)
        shot(v, "06_level1_won")
        v.startSolo(3)
        v.simulate(30f, 0f, 0.4f)
        shot(v, "07_level3_lost")
        v.startSolo(18)
        shot(v, "08_level18_tap_to_start")
        v.simulate(0.6f, 0f, 0f)
        shot(v, "09_level18")
        v.startSolo(33)
        v.simulate(0.6f, 0f, 0f)
        shot(v, "10_level33")
        v.startRush()
        shot(v, "11_rush_tap_to_start")
        v.simulate(5f, 0.03f, 0.02f)
        shot(v, "12_rush")
        v.startTug()
        shot(v, "13_tug_tap_to_start")
        v.simulate(1.2f, 0.1f, -0.2f)
        shot(v, "14_tug")
        v.simulate(120f, 0f, -0.4f)
        shot(v, "15_tug_won")
        v.startSolo(5)
        v.simulate(0.2f, 0f, 0f)
        v.onBack()
        shot(v, "16_paused")
        assertEquals(GameView.Screen.SOLO, v.screen)
    }

    /** Tablets, foldables and split-screen windows are much shorter than a phone. */
    @Test fun shortWindowsStillFit() {
        val v = launch().view
        for ((tag, w, h) in listOf(Triple("4x3", 768, 1024), Triple("split", 720, 760))) {
            v.open(GameView.Screen.MENU)
            shot(v, "20_${tag}_menu", w, h)
            v.open(GameView.Screen.LEVELS)
            shot(v, "21_${tag}_levels", w, h)
            v.open(GameView.Screen.HOWTO)
            shot(v, "22_${tag}_howto", w, h)
            v.startSolo(1)
            v.simulate(8f, 0f, -0.2f)
            shot(v, "23_${tag}_won", w, h)
            v.startSolo(7)
            v.simulate(0.3f, 0f, 0f)
            v.onBack()
            shot(v, "24_${tag}_paused", w, h)
        }
    }

    @Test fun menuWithoutATiltSensor() {
        val v = launch(withSensor = false).view
        shot(v, "30_menu_no_sensor")
    }
}
