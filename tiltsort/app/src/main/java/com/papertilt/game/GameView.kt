package com.papertilt.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import com.papertilt.game.core.Ball
import com.papertilt.game.core.BallState
import com.papertilt.game.core.Fx
import com.papertilt.game.core.FxKind
import com.papertilt.game.core.Levels
import com.papertilt.game.core.MAX_OUTS
import com.papertilt.game.core.RushGame
import com.papertilt.game.core.Size
import com.papertilt.game.core.SoloGame
import com.papertilt.game.core.TugGame
import com.papertilt.game.core.World
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The whole game UI: one custom view that draws every screen and runs the simulation. */
@SuppressLint("ViewConstructor")
class GameView(
    context: Context,
    private val tilt: Tilt,
    private val sfx: Sfx,
    private val prefs: Prefs,
) : View(context) {

    enum class Screen { MENU, LEVELS, HOWTO, SOLO, RUSH, TUG }

    var screen = Screen.MENU
        private set
    private var paused = false
    private var solo: SoloGame? = null
    private var rush: RushGame? = null
    private var tug: TugGame? = null
    private var menuWorld: World? = null
    private var newBest = false

    private var lastNs = 0L
    private var acc = 0f
    private var clock = 0f
    private var lastBump = -1f

    private var u = 1f
    private var safeTop = 0f
    private var ox = 0f
    private var oy = 0f
    private var sc = 1f

    private class Btn(val r: RectF, val action: () -> Unit)
    private class Floater(val x: Float, val y: Float, val text: String, val color: Int, val flip: Boolean) {
        var t = 0f
    }

    private val btns = ArrayList<Btn>()
    private val floaters = ArrayList<Floater>()

    private var touchId = -1
    private var touchX0 = 0f
    private var touchY0 = 0f
    private var touchTx = 0f
    private var touchTy = 0f

    private val font: Typeface = Typeface.create("casual", Typeface.BOLD)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = font
        textAlign = Paint.Align.CENTER
    }
    private val dash = DashPathEffect(floatArrayOf(2.2f, 2.2f), 0f)
    private val tmpRect = RectF()

    /** Slowly cycling seed offset that makes outlines "boil" like a flip-book doodle. */
    private val boil: Int get() = ((clock * 5f).toInt() % 3) * 101

    // ---------------------------------------------------------------- navigation

    fun startSolo(level: Int) {
        solo = SoloGame(level)
        begin(Screen.SOLO)
    }

    fun startRush() {
        rush = RushGame()
        newBest = false
        begin(Screen.RUSH)
    }

    fun startTug() {
        tug = TugGame()
        begin(Screen.TUG)
    }

    fun open(s: Screen) {
        begin(s)
    }

    private fun begin(s: Screen) {
        screen = s
        paused = false
        floaters.clear()
        acc = 0f
    }

    private val inGame: Boolean get() = screen == Screen.SOLO || screen == Screen.RUSH || screen == Screen.TUG

    private val roundOver: Boolean
        get() = when (screen) {
            Screen.SOLO -> solo?.over == true
            Screen.RUSH -> rush?.over == true
            Screen.TUG -> (tug?.winner ?: -1) >= 0
            else -> false
        }

    /** Returns true when the back press was used by the game. */
    fun onBack(): Boolean {
        when {
            screen == Screen.MENU -> return false
            screen == Screen.LEVELS || screen == Screen.HOWTO -> begin(Screen.MENU)
            roundOver -> begin(if (screen == Screen.SOLO) Screen.LEVELS else Screen.MENU)
            else -> paused = !paused
        }
        return true
    }

    fun onHostPause() {
        if (inGame && !roundOver) paused = true
        lastNs = 0L
    }

    // ---------------------------------------------------------------- simulation

    /** Run the simulation for [seconds] with a fixed tilt. Used by tests and screenshots. */
    fun simulate(seconds: Float, tx: Float, ty: Float) {
        var t = 0f
        while (t < seconds) {
            stepOnce(STEP, tx, ty)
            t += STEP
        }
    }

    private fun update(dt: Float) {
        acc += dt
        while (acc >= STEP) {
            val tugMode = screen == Screen.TUG
            var tx = if (tugMode) tilt.flatX else tilt.x
            var ty = if (tugMode) tilt.flatY else tilt.y
            if (!tilt.available) {
                tx = touchTx
                ty = touchTy
            }
            stepOnce(STEP, tx, ty)
            acc -= STEP
        }
    }

    private fun stepOnce(dt: Float, tx: Float, ty: Float) {
        clock += dt
        val it = floaters.iterator()
        while (it.hasNext()) {
            val f = it.next()
            f.t += dt
            if (f.t > FLOAT_T) it.remove()
        }
        if (!inGame) {
            menuWorld?.let {
                it.step(dt, tx, ty)
                it.events.clear()
            }
            return
        }
        if (paused) return
        when (screen) {
            Screen.SOLO -> solo?.let { g ->
                g.step(dt, tx, ty)
                drain(g.fx)
            }
            Screen.RUSH -> rush?.let { g ->
                g.step(dt, tx, ty)
                drain(g.fx)
            }
            Screen.TUG -> tug?.let { g ->
                g.step(dt, tx, ty)
                drain(g.fx)
            }
            else -> {}
        }
    }

    private fun drain(fx: ArrayList<Fx>) {
        for (f in fx) {
            when (f.kind) {
                FxKind.BUMP -> if (clock - lastBump > 0.07f) {
                    lastBump = clock
                    sfx.play(Snd.TICK)
                }
                FxKind.GOOD -> {
                    sfx.play(Snd.GOOD)
                    buzz(HapticFeedbackConstants.VIRTUAL_KEY)
                    floaters.add(Floater(f.x, f.y, if (f.n > 0) "+${f.n}" else "Yes!", GOOD, false))
                }
                FxKind.BAD -> {
                    sfx.play(Snd.BAD)
                    buzz(HapticFeedbackConstants.LONG_PRESS)
                    floaters.add(Floater(f.x, f.y, "OUT!", BAD, false))
                }
                FxKind.POINT -> {
                    sfx.play(Snd.GOOD)
                    buzz(HapticFeedbackConstants.LONG_PRESS)
                    floaters.add(Floater(f.x, f.y, "+1", PLAYER[f.n], f.n == 1))
                }
                FxKind.SPAWN -> sfx.play(Snd.POP)
                FxKind.MEND -> {}
                FxKind.SHOVE -> sfx.play(Snd.POP)
                FxKind.WIN -> {
                    sfx.play(Snd.WIN)
                    solo?.takeIf { screen == Screen.SOLO }?.let { g ->
                        prefs.setStars(g.level, g.stars)
                        if (g.level >= prefs.unlocked && g.level < Levels.COUNT) prefs.unlocked = g.level + 1
                    }
                }
                FxKind.LOSE -> {
                    sfx.play(Snd.LOSE)
                    rush?.takeIf { screen == Screen.RUSH }?.let { g ->
                        if (g.score > prefs.rushBest) {
                            prefs.rushBest = g.score
                            newBest = true
                        }
                    }
                }
            }
        }
        fx.clear()
    }

    private fun buzz(kind: Int) {
        try {
            performHapticFeedback(kind)
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------- input

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= 28) safeTop = (insets.displayCutout?.safeInsetTop ?: 0).toFloat()
        return super.onApplyWindowInsets(insets)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val x = e.getX(i)
                val y = e.getY(i)
                val b = btns.firstOrNull { it.r.contains(x, y) }
                if (b != null) {
                    sfx.play(Snd.TAP)
                    b.action()
                    btns.clear()
                } else if (screen == Screen.TUG && !paused) {
                    tug?.shove(if (y > height / 2f) 0 else 1)
                } else if (!tilt.available && touchId < 0) {
                    touchId = e.getPointerId(i)
                    touchX0 = x
                    touchY0 = y
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(touchId)
                if (i >= 0) {
                    touchTx = ((e.getX(i) - touchX0) / (25f * u)).coerceIn(-0.5f, 0.5f)
                    touchTy = ((e.getY(i) - touchY0) / (25f * u)).coerceIn(-0.5f, 0.5f)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> {
                if (e.actionMasked != MotionEvent.ACTION_POINTER_UP || e.getPointerId(e.actionIndex) == touchId) {
                    touchId = -1
                    touchTx = 0f
                    touchTy = 0f
                }
            }
        }
        return true
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0f else min(0.05f, (now - lastNs) / 1e9f)
        lastNs = now
        update(dt)
        render(canvas)
        postInvalidateOnAnimation()
    }

    private fun render(c: Canvas) {
        u = width / 100f
        btns.clear()
        drawPaper(c)
        when (screen) {
            Screen.MENU -> drawMenu(c)
            Screen.LEVELS -> drawLevels(c)
            Screen.HOWTO -> drawHowTo(c)
            Screen.SOLO -> drawSolo(c)
            Screen.RUSH -> drawRush(c)
            Screen.TUG -> drawTug(c)
        }
    }

    private fun drawPaper(c: Canvas) {
        c.drawColor(PAPER)
        ink.pathEffect = null
        ink.color = RULE
        ink.strokeWidth = 0.25f * u
        var y = 9f * u
        while (y < height) {
            c.drawLine(0f, y, width.toFloat(), y, ink)
            y += 7f * u
        }
        ink.color = MARGIN
        c.drawLine(9f * u, 0f, 9f * u, height.toFloat(), ink)
    }

    private fun txt(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.CENTER) {
        text.textSize = size
        text.color = color
        text.textAlign = align
        c.drawText(s, x, y - (text.ascent() + text.descent()) / 2f, text)
    }

    private fun button(
        c: Canvas, cx: Float, cy: Float, w: Float, h: Float, label: String, seed: Int,
        size: Float = 5.4f * u, bg: Int = CARD, fg: Int = INK, action: () -> Unit,
    ) {
        tmpRect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        fill.color = bg
        c.drawRoundRect(tmpRect, 1.5f * u, 1.5f * u, fill)
        ink.color = INK
        ink.strokeWidth = 0.55f * u
        Sketch.rect(c, tmpRect.left, tmpRect.top, tmpRect.right, tmpRect.bottom, ink, seed + boil, 0.9f * u)
        txt(c, label, cx, cy, size, fg)
        btns.add(Btn(RectF(tmpRect), action))
    }

    private fun fitWorld(w: World, top: Float, bottom: Float) {
        val availW = width - 8f * u
        val availH = bottom - top
        sc = min(availW / w.w, availH / w.h)
        ox = (width - w.w * sc) / 2f
        oy = top + (availH - w.h * sc) / 2f
    }

    private fun sizeColor(s: Size): Int = when (s) {
        Size.S -> RED
        Size.M -> BLUE
        Size.L -> GREEN
    }

    private fun drawWorld(c: Canvas, w: World, tugMode: Boolean = false) {
        c.save()
        c.translate(ox, oy)
        c.scale(sc, sc)

        fill.color = TRAY
        c.drawRect(0f, 0f, w.w, w.h, fill)
        if (tugMode) {
            fill.color = (PLAYER[1] and 0xFFFFFF) or 0x30000000
            c.drawRect(0f, 0f, w.w, 7f, fill)
            fill.color = (PLAYER[0] and 0xFFFFFF) or 0x30000000
            c.drawRect(0f, w.h - 7f, w.w, w.h, fill)
        }

        for ((zi, z) in w.sand.withIndex()) {
            fill.color = SAND
            c.drawRect(z.x, z.y, z.x + z.w, z.y + z.h, fill)
            fill.color = SAND_DOT
            for (i in 0 until 46) {
                c.drawCircle(
                    z.x + (Sketch.rnd(zi * 53 + 5, i) + 0.5f) * z.w,
                    z.y + (Sketch.rnd(zi * 53 + 9, i) + 0.5f) * z.h, 0.38f, fill,
                )
            }
        }

        // Dashed outline: an edge with only this line is open, and a ball can roll off it.
        ink.color = 0x662B2B33
        ink.strokeWidth = 0.4f
        ink.pathEffect = dash
        c.drawRect(0f, 0f, w.w, w.h, ink)
        ink.pathEffect = null

        for ((i, h) in w.holes.withIndex()) {
            fill.color = INK
            c.drawCircle(h.x, h.y, h.r, fill)
            fill.color = 0x16FFFFFF
            c.drawCircle(h.x + h.r * 0.12f, h.y + h.r * 0.16f, h.r * 0.78f, fill)
            ink.color = sizeColor(h.size)
            ink.strokeWidth = 0.9f
            Sketch.circle(c, h.x, h.y, h.r + 0.7f, ink, i * 37 + 3 + boil, 0.07f)
        }

        ink.color = WALL
        ink.strokeWidth = World.WALL_T * 2f
        for ((i, wl) in w.walls.withIndex()) {
            Sketch.line(c, wl.x1, wl.y1, wl.x2, wl.y2, ink, i * 41 + 11, 0.5f)
        }

        for (b in w.balls) if (b.state == BallState.HOME) drawBall(c, b, w)
        for (b in w.balls) if (b.state != BallState.HOME) drawBall(c, b, w, if (tugMode) STEEL else 0)
        c.restore()
    }

    private fun drawBall(c: Canvas, b: Ball, w: World, tint: Int = 0) {
        val k = when (b.state) {
            BallState.SPAWNING -> b.t / World.SPAWN_T
            BallState.SINKING ->
                if (b.sunkOk && w.holesFill) 1f - 0.16f * (b.t / World.SINK_T) else 1f - b.t / World.SINK_T
            BallState.FALLING -> 1f - b.t / World.FALL_T
            BallState.HOME -> 0.84f
            BallState.ROLLING -> 1f
            BallState.GONE -> return
        }.coerceIn(0f, 1f)
        val r = b.r * k
        if (r < 0.2f) return
        fill.color = if (tint != 0) tint else sizeColor(b.size)
        c.drawCircle(b.x, b.y, r, fill)
        if (b.state == BallState.HOME) {
            fill.color = 0x40000000
            c.drawCircle(b.x, b.y, r, fill)
        }
        fill.color = 0x66FFFFFF
        c.drawCircle(b.x - r * 0.3f, b.y - r * 0.35f, r * 0.27f, fill)
        fill.color = 0x48000000
        c.drawCircle(b.x + cos(b.roll) * r * 0.55f, b.y + sin(b.roll) * r * 0.55f, r * 0.13f, fill)
        ink.color = INK
        ink.strokeWidth = 0.5f
        Sketch.circle(c, b.x, b.y, r, ink, b.id * 17 + 5 + boil, 0.06f)
    }

    private fun drawFloaters(c: Canvas) {
        for (f in floaters) {
            val k = f.t / FLOAT_T
            val x = ox + f.x * sc
            val y = oy + f.y * sc
            val color = (f.color and 0xFFFFFF) or (((1f - k * k) * 255).toInt() shl 24)
            if (f.flip) {
                c.save()
                c.rotate(180f, x, y)
                txt(c, f.text, x, y - 9f * u * k, 6.5f * u, color)
                c.restore()
            } else {
                txt(c, f.text, x, y - 9f * u * k, 6.5f * u, color)
            }
        }
    }

    private fun drawOuts(c: Canvas, x: Float, y: Float, outs: Int) {
        val s = 5.2f * u
        for (i in 0 until MAX_OUTS) {
            val l = x + i * (s + 1.6f * u)
            ink.color = INK
            ink.strokeWidth = 0.45f * u
            Sketch.rect(c, l, y - s / 2, l + s, y + s / 2, ink, 500 + i, 0.5f * u)
            if (i < outs) {
                ink.color = BAD
                ink.strokeWidth = 0.8f * u
                Sketch.line(c, l + s * 0.15f, y - s * 0.35f, l + s * 0.85f, y + s * 0.35f, ink, 520 + i, 0.5f * u)
                Sketch.line(c, l + s * 0.85f, y - s * 0.35f, l + s * 0.15f, y + s * 0.35f, ink, 540 + i, 0.5f * u)
            }
        }
    }

    private fun pauseButton(c: Canvas, cx: Float, cy: Float) {
        button(c, cx, cy, 10f * u, 9f * u, "II", 77, size = 5f * u) { paused = true }
    }

    /** Dims the screen, draws a card and returns the y of its top edge. */
    private fun panel(c: Canvas, h: Float, cy: Float = height / 2f): Float {
        btns.clear()
        c.drawColor(0xB0FBF7EA.toInt())
        val l = 10f * u
        val r = width - 10f * u
        val t = cy - h / 2
        fill.color = CARD
        tmpRect.set(l, t, r, t + h)
        c.drawRoundRect(tmpRect, 2f * u, 2f * u, fill)
        ink.color = INK
        ink.strokeWidth = 0.7f * u
        Sketch.rect(c, l, t, r, t + h, ink, 900 + boil, 1.1f * u)
        return t
    }

    private fun pausePanel(c: Canvas, restart: () -> Unit, showCalibrate: Boolean) {
        val n = if (showCalibrate) 4 else 3
        val h = (24f + n * 15f) * u
        val t = panel(c, h)
        val cx = width / 2f
        txt(c, "Paused", cx, t + 11f * u, 8f * u, INK)
        var y = t + 28f * u
        button(c, cx, y, 60f * u, 11f * u, "Resume", 910) { paused = false }
        y += 15f * u
        if (showCalibrate) {
            button(c, cx, y, 60f * u, 11f * u, "Set level here", 911, size = 4.8f * u) {
                tilt.calibrate()
                paused = false
            }
            y += 15f * u
        }
        button(c, cx, y, 60f * u, 11f * u, "Restart", 912) { restart() }
        y += 15f * u
        button(c, cx, y, 60f * u, 11f * u, "Menu", 913) { begin(Screen.MENU) }
    }

    // ---- screens

    private fun drawMenu(c: Canvas) {
        // Loose marbles roll around the whole page behind the menu.
        val aspect = height / width.toFloat()
        val mw = menuWorld?.takeIf { kotlin.math.abs(it.h - Levels.W * aspect) < 0.5f }
            ?: Levels.menuWorld(Levels.W * aspect).also { menuWorld = it }
        ox = 0f
        oy = 0f
        sc = u
        c.save()
        c.scale(sc, sc)
        for (b in mw.balls) drawBall(c, b, mw)
        c.restore()
        val cx = width / 2f
        val top = safeTop + height * 0.16f
        txt(c, "Tilt & Sort", cx, top, 14f * u, INK)
        txt(c, "a paper marble game", cx, top + 12f * u, 4.6f * u, 0xFF6A6A75.toInt())
        var y = height * 0.42f
        val bw = 70f * u
        val bh = 13f * u
        button(c, cx, y, bw, bh, "Levels", 1) { begin(Screen.LEVELS) }
        y += 17f * u
        val best = prefs.rushBest
        button(c, cx, y, bw, bh, if (best > 0) "Rush  (best $best)" else "Rush", 2) { startRush() }
        y += 17f * u
        button(c, cx, y, bw, bh, "2 Players: Tug", 3) { startTug() }
        y += 17f * u
        button(c, cx, y, bw, bh, "How to play", 4) { begin(Screen.HOWTO) }
        y += 16f * u
        button(c, cx, y, 44f * u, 9.5f * u, if (sfx.enabled) "Sound: on" else "Sound: off", 5, size = 4.2f * u) {
            sfx.enabled = !sfx.enabled
            prefs.sound = sfx.enabled
        }
        if (!tilt.available) {
            txt(c, "No tilt sensor found: drag to tilt.", cx, height - 8f * u, 3.8f * u, BAD)
        }
    }

    private fun drawLevels(c: Canvas) {
        val top = safeTop + 10f * u
        txt(c, "Levels", width / 2f, top, 9f * u, INK)
        button(c, 12f * u, top, 14f * u, 10f * u, "<", 20) { begin(Screen.MENU) }
        val cols = 5
        val cell = 15.5f * u
        val gap = 2.4f * u
        val gridW = cols * cell + (cols - 1) * gap
        val x0 = (width - gridW) / 2f
        val y0 = top + 12f * u
        val unlocked = prefs.unlocked
        for (n in 1..Levels.COUNT) {
            val col = (n - 1) % cols
            val row = (n - 1) / cols
            val cx = x0 + col * (cell + gap) + cell / 2
            val cy = y0 + row * (cell + gap) + cell / 2
            if (n <= unlocked) {
                button(c, cx, cy, cell, cell, "", 100 + n) { startSolo(n) }
                txt(c, "$n", cx, cy - 2f * u, 6f * u, INK)
                val stars = prefs.stars(n)
                for (s in 0 until 3) {
                    fill.color = if (s < stars) STAR else 0xFFD9D4C4.toInt()
                    Sketch.star(c, cx + (s - 1) * 4f * u, cy + 4.6f * u, 1.9f * u, fill)
                }
            } else {
                ink.color = 0x552B2B33
                ink.strokeWidth = 0.4f * u
                Sketch.rect(c, cx - cell / 2, cy - cell / 2, cx + cell / 2, cy + cell / 2, ink, 100 + n, 0.8f * u)
                txt(c, "$n", cx, cy, 6f * u, 0x552B2B33)
            }
        }
    }

    private fun drawHowTo(c: Canvas) {
        val top = safeTop + 10f * u
        txt(c, "How to play", width / 2f, top, 8f * u, INK)
        button(c, 12f * u, top, 14f * u, 10f * u, "<", 20) { begin(Screen.MENU) }
        val lines = listOf(
            "#Levels",
            "Tilt the phone to roll the balls.",
            "Drop each ball in the hole of its",
            "own size and colour.",
            "A small ball in a bigger hole is OUT.",
            "Rolling off a dashed edge is OUT.",
            "Three outs and the level is lost.",
            "Big balls roll over small holes.",
            "",
            "#Rush",
            "Balls keep coming. Sink them in a",
            "row to grow your multiplier.",
            "",
            "#2 Players: Tug",
            "Put the phone flat between you.",
            "Both tilt it! Roll marbles out of",
            "your rival's end to score.",
            "Tap your half to shove. First to ${TugGame.WIN_SCORE}.",
            "",
            "#Tip",
            "Pause and pick \"Set level here\" to",
            "play with the phone held at an angle.",
        )
        var y = top + 14f * u
        for (l in lines) {
            if (l.startsWith("#")) {
                txt(c, l.substring(1), 13f * u, y, 5.6f * u, BLUE, Paint.Align.LEFT)
            } else if (l.isNotEmpty()) {
                txt(c, l, 13f * u, y, 4.3f * u, INK, Paint.Align.LEFT)
            }
            y += if (l.isEmpty()) 3f * u else 7f * u
        }
    }

    private fun drawSolo(c: Canvas) {
        val g = solo ?: return
        val hudY = safeTop + 7.5f * u
        fitWorld(g.world, safeTop + 14f * u, height - (if (g.hint != null) 15f else 3f) * u)
        drawWorld(c, g.world)
        drawFloaters(c)
        txt(c, "Level ${g.level}", 5f * u, hudY, 5.6f * u, INK, Paint.Align.LEFT)
        val secs = g.time.toInt()
        txt(c, "%d:%02d".format(secs / 60, secs % 60), 43f * u, hudY, 5.2f * u, 0xFF6A6A75.toInt())
        drawOuts(c, 56f * u, hudY, g.outs)
        g.hint?.let { hint ->
            val parts = hint.split("\n")
            var y = height - 10.5f * u + (2 - parts.size) * 3f * u
            for (p in parts) {
                txt(c, p, width / 2f, y, 4.3f * u, INK)
                y += 6f * u
            }
        }
        when {
            g.won -> {
                val last = g.level >= Levels.COUNT
                val t = panel(c, 74f * u)
                val cx = width / 2f
                txt(c, if (last) "All levels done!" else "Sorted!", cx, t + 11f * u, 8f * u, GOOD)
                for (s in 0 until 3) {
                    fill.color = if (s < g.stars) STAR else 0xFFD9D4C4.toInt()
                    Sketch.star(c, cx + (s - 1) * 14f * u, t + 26f * u, 6f * u, fill)
                }
                val secsWon = g.time.toInt()
                txt(c, "Time %d:%02d   Outs %d".format(secsWon / 60, secsWon % 60, g.outs), cx, t + 38f * u, 4.4f * u, INK)
                if (!last) {
                    button(c, cx, t + 51f * u, 60f * u, 11f * u, "Next level", 920) { startSolo(g.level + 1) }
                } else {
                    button(c, cx, t + 51f * u, 60f * u, 11f * u, "Play Rush", 920) { startRush() }
                }
                button(c, cx - 16f * u, t + 65f * u, 28f * u, 10f * u, "Again", 921, size = 4.6f * u) { startSolo(g.level) }
                button(c, cx + 16f * u, t + 65f * u, 28f * u, 10f * u, "Levels", 922, size = 4.6f * u) { begin(Screen.LEVELS) }
            }
            g.lost -> {
                val t = panel(c, 52f * u)
                val cx = width / 2f
                txt(c, "Three outs!", cx, t + 11f * u, 8f * u, BAD)
                button(c, cx, t + 27f * u, 60f * u, 11f * u, "Try again", 930) { startSolo(g.level) }
                button(c, cx, t + 42f * u, 60f * u, 11f * u, "Levels", 931) { begin(Screen.LEVELS) }
            }
            paused -> pausePanel(c, { startSolo(g.level) }, tilt.available)
            else -> pauseButton(c, width - 8f * u, hudY)
        }
    }

    private fun drawRush(c: Canvas) {
        val g = rush ?: return
        val hudY = safeTop + 7.5f * u
        fitWorld(g.world, safeTop + 14f * u, height - 3f * u)
        drawWorld(c, g.world)
        drawFloaters(c)
        txt(c, "${g.score}", 5f * u, hudY, 7f * u, INK, Paint.Align.LEFT)
        if (g.multiplier > 1) txt(c, "x${g.multiplier}", 43f * u, hudY, 5.6f * u, GOOD)
        drawOuts(c, 56f * u, hudY, g.outs)
        when {
            g.over -> {
                val t = panel(c, 66f * u)
                val cx = width / 2f
                txt(c, "Three outs!", cx, t + 11f * u, 8f * u, BAD)
                txt(c, "Score ${g.score}", cx, t + 24f * u, 7f * u, INK)
                txt(
                    c, if (newBest) "New best!" else "Best ${prefs.rushBest}", cx, t + 33f * u, 4.6f * u,
                    if (newBest) GOOD else 0xFF6A6A75.toInt(),
                )
                button(c, cx, t + 44f * u, 60f * u, 11f * u, "Play again", 940) { startRush() }
                button(c, cx, t + 58f * u, 60f * u, 11f * u, "Menu", 941) { begin(Screen.MENU) }
            }
            paused -> pausePanel(c, { startRush() }, tilt.available)
            else -> pauseButton(c, width - 8f * u, hudY)
        }
    }

    private fun drawTug(c: Canvas) {
        val g = tug ?: return
        fitWorld(g.world, safeTop + 3f * u, height - 3f * u)
        drawWorld(c, g.world, tugMode = true)
        val cx = width / 2f
        val midY = oy + g.world.h * sc / 2f
        // Each player's score and shove meter sit in their own half, facing them.
        for (p in 0..1) {
            val y = oy + g.world.h * sc * (if (p == 0) 0.70f else 0.30f)
            val labelY = oy + g.world.h * sc * (if (p == 0) 0.92f else 0.08f)
            val ready = g.cooldown[p] <= 0f
            c.save()
            if (p == 1) c.rotate(180f, cx, y)
            txt(c, "${g.score[p]}", cx, y, 20f * u, (PLAYER[p] and 0xFFFFFF) or 0x70000000)
            c.restore()
            c.save()
            if (p == 1) c.rotate(180f, cx, labelY)
            txt(
                c, if (ready) "tap to shove" else "...", cx, labelY, 4.2f * u,
                (PLAYER[p] and 0xFFFFFF) or (if (ready) 0xD0000000.toInt() else 0x60000000),
            )
            c.restore()
        }
        drawFloaters(c)
        when {
            g.winner >= 0 -> {
                val t = panel(c, 44f * u)
                val name = if (g.winner == 0) "Blue wins!" else "Orange wins!"
                txt(c, name, cx, t + 34f * u, 8f * u, PLAYER[g.winner])
                c.save()
                c.rotate(180f, cx, t + 10f * u)
                txt(c, name, cx, t + 10f * u, 8f * u, PLAYER[g.winner])
                c.restore()
                button(c, cx - 19f * u, t + 22f * u, 34f * u, 10f * u, "Rematch", 950, size = 4.6f * u) { startTug() }
                button(c, cx + 19f * u, t + 22f * u, 34f * u, 10f * u, "Menu", 951, size = 4.6f * u) { begin(Screen.MENU) }
            }
            paused -> pausePanel(c, { startTug() }, false)
            else -> pauseButton(c, width - 6.5f * u, midY)
        }
    }

    companion object {
        const val STEP = 1f / 120f
        const val FLOAT_T = 0.9f

        val PAPER = 0xFFFBF7EA.toInt()
        val TRAY = 0xFFFFFDF6.toInt()
        val CARD = 0xFFFFFDF6.toInt()
        val RULE = 0xFFC9DDEE.toInt()
        val MARGIN = 0xFFEDB4B4.toInt()
        val INK = 0xFF2B2B33.toInt()
        val WALL = 0xFF3D3D48.toInt()
        val RED = 0xFFE4572E.toInt()
        val BLUE = 0xFF2E86AB.toInt()
        val GREEN = 0xFF3BA55D.toInt()
        val GOOD = 0xFF2E9950.toInt()
        val BAD = 0xFFD23A2A.toInt()
        val STEEL = 0xFFA9AEB8.toInt()
        val STAR = 0xFFF2B01E.toInt()
        val SAND = 0xFFF1E3B4.toInt()
        val SAND_DOT = 0xFFC7AE66.toInt()
        val PLAYER = intArrayOf(0xFF2E6FCF.toInt(), 0xFFE07B1A.toInt())
    }
}
