package com.papertilt.game

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.io.File
import java.io.FileOutputStream
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the Play Store listing images from the game's own drawing code: phone screenshots,
 * the 512 px icon and the 1024 x 500 feature graphic. Only runs when STORE=1 is set:
 *   STORE=1 ./gradlew cleanTestDebugUnitTest testDebugUnitTest --tests '*StoreAssets*'
 * Output goes to app/build/store/; copy it to store/graphics/ when the look of the game changes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class StoreAssets {
    private val dir = File("build/store")

    private fun save(bmp: Bitmap, name: String) {
        dir.mkdirs()
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("casual", Typeface.BOLD) }

    private fun paper(c: Canvas, w: Int, h: Int, pitch: Float, margin: Float) {
        c.drawColor(GameView.PAPER)
        ink.color = GameView.RULE
        ink.strokeWidth = pitch / 26f
        var y = pitch
        while (y < h) {
            c.drawLine(0f, y, w.toFloat(), y, ink)
            y += pitch
        }
        ink.color = GameView.MARGIN
        c.drawLine(margin, 0f, margin, h.toFloat(), ink)
    }

    private fun hole(c: Canvas, x: Float, y: Float, r: Float, ring: Int, seed: Int) {
        fill.color = GameView.INK
        c.drawCircle(x, y, r, fill)
        fill.color = 0x16FFFFFF
        c.drawCircle(x + r * 0.12f, y + r * 0.16f, r * 0.78f, fill)
        ink.color = ring
        ink.strokeWidth = r * 0.13f
        Sketch.circle(c, x, y, r * 1.1f, ink, seed, 0.07f)
    }

    private fun ball(c: Canvas, x: Float, y: Float, r: Float, color: Int, seed: Int) {
        fill.color = color
        c.drawCircle(x, y, r, fill)
        fill.color = 0x66FFFFFF
        c.drawCircle(x - r * 0.3f, y - r * 0.35f, r * 0.27f, fill)
        fill.color = 0x48000000
        c.drawCircle(x + r * 0.4f, y + r * 0.35f, r * 0.13f, fill)
        ink.color = GameView.INK
        ink.strokeWidth = r * 0.1f
        Sketch.circle(c, x, y, r, ink, seed, 0.06f)
    }

    @Test fun draw() {
        Assume.assumeTrue(System.getenv("STORE") != null)

        // Icon: full square, no rounding or shadow (Play adds its own mask).
        val icon = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        Canvas(icon).let { c ->
            paper(c, 512, 512, 64f, 96f)
            hole(c, 205f, 215f, 104f, GameView.BLUE, 3)
            ball(c, 345f, 345f, 82f, GameView.RED, 5)
        }
        save(icon, "icon-512.png")

        // Feature graphic.
        val feature = Bitmap.createBitmap(1024, 500, Bitmap.Config.ARGB_8888)
        Canvas(feature).let { c ->
            paper(c, 1024, 500, 62f, 70f)
            text.color = GameView.INK
            text.textSize = 132f
            c.drawText("Tilt & Sort", 110f, 240f, text)
            text.color = GameView.GREY
            text.textSize = 46f
            c.drawText("a paper marble game", 118f, 320f, text)
            hole(c, 880f, 140f, 70f, GameView.GREEN, 7)
            hole(c, 930f, 330f, 46f, GameView.RED, 9)
            hole(c, 720f, 370f, 56f, GameView.BLUE, 11)
            ball(c, 610f, 400f, 30f, GameView.RED, 13)
            ball(c, 790f, 255f, 56f, GameView.GREEN, 15)
            ball(c, 500f, 410f, 42f, GameView.BLUE, 17)
        }
        save(feature, "feature-1024x500.png")

        // Phone screenshots, 9:16 (Play allows at most 2:1).
        val activity = launch()
        val v = activity.view
        val prefs = Prefs(activity)
        prefs.unlocked = 14
        for (n in 1..13) prefs.recordWin(n, if (n % 3 == 0) 3 else 7, 9f)
        fun shot(name: String) = save(draw(v, 1080, 1920), name)
        v.open(GameView.Screen.MENU)
        draw(v, 1080, 1920) // the menu's marbles exist from the first frame
        v.simulate(3f, 0.05f, 0.35f)
        shot("phone-1-menu.png")
        v.startSolo(9)
        v.simulate(1.0f, 0.12f, -0.1f)
        shot("phone-2-level.png")
        v.startSolo(22)
        v.simulate(0.5f, 0.05f, 0.03f)
        shot("phone-3-late-level.png")
        v.startRush()
        v.simulate(3.2f, 0f, 0f)
        shot("phone-4-rush.png")
        v.startTug()
        v.simulate(1.2f, 0.1f, -0.2f)
        shot("phone-5-tug.png")
        v.startSolo(1)
        v.simulate(8f, 0f, -0.2f)
        shot("phone-6-stars.png")
        v.open(GameView.Screen.LEVELS)
        shot("phone-7-levels.png")
    }
}
