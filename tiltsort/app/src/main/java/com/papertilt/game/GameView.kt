package com.papertilt.game

import android.annotation.SuppressLint
import android.app.Activity
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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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
    var paused = false
        private set

    /** False from when a round is set up until the player taps to start it; nothing moves before that. */
    var ready = true
        private set
    private var tooSteep = false
    var solo: SoloGame? = null
        private set
    var rush: RushGame? = null
        private set
    var tug: TugGame? = null
        private set
    private var menuWorld: World? = null
    private var newBest = false
    private var fasterThanBefore = false

    private var lastNs = 0L

    /** Seconds of drawn time since launch. A Double, so that it keeps counting however long the game stays open. */
    private var clock = 0.0
    private var lastBump = -1.0

    /** Buttons ignore taps until the clock reaches this; see [TAP_GUARD]. */
    private var armAt = 0.0

    /** Clock time at which the round ended, or -1. The result panel follows [RESULT_DELAY] later. */
    private var overAt = -1.0
    private var starsRung = 0
    private var shake = 0f
    private var lastMult = 1
    private var multPulse = 0f

    private var u = 1f
    private var safeTop = 0f
    private var ox = 0f
    private var oy = 0f
    private var sc = 1f
    private var tugMidY = 0f

    private class Btn(val r: RectF, val action: () -> Unit)
    private class Floater(val x: Float, val y: Float, val text: String, val color: Int, val flip: Boolean) {
        var t = 0f
    }
    private class Burst(val x: Float, val y: Float, val color: Int) {
        var t = 0f
    }

    private val btns = ArrayList<Btn>()
    private val floaters = ArrayList<Floater>()
    private val bursts = ArrayList<Burst>()

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
    private var dash: DashPathEffect? = null
    private var dashZoom = 0f
    private val tmpRect = RectF()

    /** Slowly cycling seed offset that makes outlines "boil" like a flip-book doodle. */
    private val boil: Int get() = ((clock * 5f).toInt() % 3) * 101

    // ---------------------------------------------------------------- navigation

    fun startSolo(level: Int) {
        solo = SoloGame(level)
        begin(Screen.SOLO)
    }

    fun startRush() {
        bankRush()
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
        if (screen == Screen.RUSH && s != Screen.RUSH) bankRush()
        screen = s
        paused = false
        ready = !inGame
        tooSteep = false
        overAt = -1.0
        starsRung = 0
        shake = 0f
        lastMult = 1
        multPulse = 0f
        floaters.clear()
        bursts.clear()
        armAt = clock + TAP_GUARD
    }

    private val inGame: Boolean get() = screen == Screen.SOLO || screen == Screen.RUSH || screen == Screen.TUG

    private val roundOver: Boolean
        get() = when (screen) {
            Screen.SOLO -> solo?.over == true
            Screen.RUSH -> rush?.over == true
            Screen.TUG -> (tug?.winner ?: -1) >= 0
            else -> false
        }

    /** The round has ended and the short beat before its result panel has passed. */
    private val resultDue: Boolean get() = overAt >= 0f && clock - overAt >= RESULT_DELAY

    /** Returns true when the back press was used by the game. */
    fun onBack(): Boolean {
        when {
            screen == Screen.MENU -> return false
            screen == Screen.LEVELS || screen == Screen.HOWTO -> begin(Screen.MENU)
            roundOver || !ready -> leaveRound()
            paused -> {
                paused = false
                tooSteep = false
            }
            else -> pause()
        }
        return true
    }

    private fun leaveRound() {
        begin(if (screen == Screen.SOLO) Screen.LEVELS else Screen.MENU)
    }

    /**
     * True when play itself puts fingers on the glass: Tug's shove taps, or dragging to tilt on
     * a device with no sensor. A panel that appears then waits a moment before its buttons work.
     */
    private val fingersOnScreen: Boolean get() = screen == Screen.TUG || !tilt.available

    private fun pause() {
        if (!inGame || !ready || roundOver || paused) return
        paused = true
        tooSteep = false
        armAt = clock + if (fingersOnScreen) PANEL_GUARD else TAP_GUARD
    }

    /** The activity is no longer in front (paused, or covered by the notification shade or a dialog). */
    fun onHostPause() {
        pause()
        if (screen == Screen.RUSH) bankRush()
        lastNs = 0L
    }

    /** Keeps a Rush score that beats the saved best, even when the run is abandoned. */
    private fun bankRush() {
        val g = rush ?: return
        if (g.score > prefs.rushBest) {
            prefs.rushBest = g.score
            newBest = true
        }
    }

    /** The tap that starts a round. However the phone is held at that moment becomes "level". */
    private fun startRound() {
        if (screen == Screen.TUG) {
            tilt.zeroFlat()
            // Both players tap their own card. The shoves start out recharging, so the second
            // of those taps is not taken as a shove.
            tug?.cooldown?.fill(TUG_KICKOFF)
        } else if (tilt.available && !tilt.calibrate()) {
            tooSteep = true
            buzz(HapticFeedbackConstants.LONG_PRESS)
            return
        }
        tooSteep = false
        ready = true
        sfx.play(Snd.POP)
    }

    // ---------------------------------------------------------------- simulation

    /**
     * Run for [seconds] with a fixed tilt, as if frames were being drawn. Used by tests and
     * screenshots. With [start] the round is started first, as the player's tap would.
     */
    fun simulate(seconds: Float, tx: Float, ty: Float, start: Boolean = true) {
        if (start) ready = true
        var t = 0f
        while (t < seconds) {
            stepOnce(STEP, tx, ty)
            t += STEP
        }
    }

    /** Advance by one frame's worth of time, in equal slices no longer than [STEP]. */
    private fun update(dt: Float) {
        if (dt <= 0f) return
        val n = max(1, ceil(dt / STEP).toInt())
        val h = dt / n
        repeat(n) {
            val tugMode = screen == Screen.TUG
            var tx = if (tugMode) tilt.flatX else tilt.x
            var ty = if (tugMode) tilt.flatY else tilt.y
            if (!tilt.available) {
                tx = touchTx
                ty = touchTy
            }
            stepOnce(h, tx, ty)
        }
    }

    private fun stepOnce(dt: Float, tx: Float, ty: Float) {
        clock += dt
        val fi = floaters.iterator()
        while (fi.hasNext()) {
            val f = fi.next()
            f.t += dt
            if (f.t > FLOAT_T) fi.remove()
        }
        val bi = bursts.iterator()
        while (bi.hasNext()) {
            val b = bi.next()
            b.t += dt
            if (b.t > BURST_T) bi.remove()
        }
        shake = max(0f, shake - dt)
        multPulse = max(0f, multPulse - dt)
        if (!inGame) {
            menuWorld?.let {
                it.step(dt, tx, ty)
                it.events.clear()
            }
            return
        }
        ringStars()
        if (paused || !ready) return
        when (screen) {
            Screen.SOLO -> solo?.let { g ->
                g.step(dt, tx, ty)
                drain(g.fx)
            }
            Screen.RUSH -> rush?.let { g ->
                g.step(dt, tx, ty)
                drain(g.fx)
                if (g.multiplier != lastMult) {
                    if (g.multiplier > lastMult) multPulse = PULSE_T
                    lastMult = g.multiplier
                }
            }
            Screen.TUG -> tug?.let { g ->
                g.step(dt, tx, ty)
                drain(g.fx)
            }
            else -> {}
        }
    }

    /** A step up a major scale, as a playback rate: each sink in a row chimes higher than the last. */
    private fun note(step: Int): Float = 2f.pow(SCALE[step.coerceIn(0, SCALE.size - 1)] / 12f)

    /** How many of the three goal rows on the win panel have been revealed so far. */
    private val rowsShown: Int
        get() = if (overAt < 0f) 0 else ((clock - overAt - RESULT_DELAY) / STAR_GAP).toInt().coerceIn(0, 3)

    private fun ringStars() {
        val g = solo ?: return
        if (screen != Screen.SOLO || !g.won) return
        while (starsRung < rowsShown) {
            if (g.goals and (1 shl starsRung) != 0) sfx.play(Snd.GOOD, note(3 + starsRung * 2))
            starsRung++
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
                    val step = when (screen) {
                        Screen.SOLO -> (solo?.home ?: 1) - 1
                        Screen.RUSH -> (rush?.streak ?: 1) - 1
                        else -> 0
                    }
                    sfx.play(Snd.GOOD, note(step))
                    buzz(HapticFeedbackConstants.VIRTUAL_KEY)
                    floaters.add(Floater(f.x, f.y, if (f.n > 0) "+${f.n}" else "Yes!", GOOD, false))
                    bursts.add(Burst(f.x, f.y, GOOD))
                }
                FxKind.BAD -> {
                    sfx.play(Snd.BAD)
                    buzz(HapticFeedbackConstants.LONG_PRESS)
                    floaters.add(Floater(f.x, f.y, "OUT!", BAD, false))
                    bursts.add(Burst(f.x, f.y, BAD))
                    shake = SHAKE_T
                }
                FxKind.POINT -> {
                    sfx.play(Snd.GOOD)
                    buzz(HapticFeedbackConstants.LONG_PRESS)
                    floaters.add(Floater(f.x, f.y, "+1", PLAYER[f.n], f.n == 1))
                    bursts.add(Burst(f.x, f.y, PLAYER[f.n]))
                }
                FxKind.MEND -> {
                    sfx.play(Snd.WIN, 1.5f)
                    floaters.add(Floater(f.x, f.y + 11f, "out erased!", GOOD, false))
                }
                FxKind.SPAWN -> sfx.play(Snd.POP)
                FxKind.SHOVE -> sfx.play(Snd.POP)
                FxKind.WIN -> {
                    sfx.play(Snd.WIN)
                    overAt = clock
                    armAt = clock + RESULT_DELAY + if (fingersOnScreen) PANEL_GUARD else 0f
                    solo?.takeIf { screen == Screen.SOLO }?.let { g ->
                        fasterThanBefore = prefs.recordWin(g.level, g.goals, g.time)
                        if (g.level >= prefs.unlocked && g.level < Levels.COUNT) prefs.unlocked = g.level + 1
                    }
                }
                FxKind.LOSE -> {
                    sfx.play(Snd.LOSE)
                    overAt = clock
                    armAt = clock + RESULT_DELAY + if (fingersOnScreen) PANEL_GUARD else 0f
                    if (screen == Screen.RUSH) bankRush()
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
        var top = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout?.safeInsetTop ?: 0 else 0
        // Before Android 10 a split-screen window keeps its status bar and still draws under it.
        if (Build.VERSION.SDK_INT < 29 && (context as? Activity)?.isInMultiWindowMode == true) {
            @Suppress("DEPRECATION")
            top = max(top, insets.systemWindowInsetTop)
        }
        safeTop = top.toFloat()
        return super.onApplyWindowInsets(insets)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val x = e.getX(i)
                val y = e.getY(i)
                val armed = clock >= armAt
                val b = if (armed) btns.firstOrNull { it.r.contains(x, y) } else null
                when {
                    b != null -> {
                        sfx.play(Snd.TAP)
                        b.action()
                        btns.clear()
                    }
                    inGame && !paused && !ready -> if (armed) startRound()
                    screen == Screen.TUG && !paused && !roundOver -> tug?.shove(if (y > tugMidY) 0 else 1)
                    !tilt.available && touchId < 0 -> {
                        touchId = e.getPointerId(i)
                        touchX0 = x
                        touchY0 = y
                    }
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
        // Everything is laid out in units of u, normally a hundredth of the width. On a window
        // too short for a screen's content (tablets, foldables, split-screen) u shrinks to fit.
        val need = when (screen) {
            Screen.MENU -> 150f
            Screen.LEVELS -> 166f
            Screen.HOWTO -> 168f
            else -> 104f
        }
        u = min(width / 100f, (height - safeTop) / need)
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
        val pu = width / 100f
        c.drawColor(PAPER)
        ink.pathEffect = null
        ink.color = RULE
        ink.strokeWidth = 0.25f * pu
        var y = 9f * pu
        while (y < height) {
            c.drawLine(0f, y, width.toFloat(), y, ink)
            y += 7f * pu
        }
        ink.color = MARGIN
        c.drawLine(9f * pu, 0f, 9f * pu, height.toFloat(), ink)
    }

    private fun txt(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.CENTER) {
        text.textSize = size
        text.color = color
        text.textAlign = align
        c.drawText(s, x, y - (text.ascent() + text.descent()) / 2f, text)
    }

    private fun clockText(seconds: Float, tenths: Boolean = false): String {
        val whole = seconds.toInt()
        return if (tenths) {
            "%d:%02d.%d".format(whole / 60, whole % 60, ((seconds - whole) * 10).toInt().coerceIn(0, 9))
        } else {
            "%d:%02d".format(whole / 60, whole % 60)
        }
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
        if (shake > 0f) {
            // A short, small jolt when a ball is lost.
            val a = 1.1f * u * shake / SHAKE_T
            ox += sin(clock * 95.0).toFloat() * a
            oy += cos(clock * 83.0).toFloat() * a
        }
    }

    private fun sizeColor(s: Size): Int = when (s) {
        Size.S -> RED
        Size.M -> BLUE
        Size.L -> GREEN
    }

    /** Draws the tray in its own units; [sc] pixels to a unit, top-left corner at ([ox], [oy]). */
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
        if (dash == null || dashZoom != sc) {
            dash = DashPathEffect(floatArrayOf(2.2f * sc, 2.2f * sc), 0f)
            dashZoom = sc
        }
        ink.color = 0x802B2B33.toInt()
        ink.strokeWidth = 0.45f
        ink.pathEffect = dash
        Sketch.box(c, 0f, 0f, w.w, w.h, ink, sc)
        ink.pathEffect = null

        for ((i, h) in w.holes.withIndex()) {
            fill.color = INK
            c.drawCircle(h.x, h.y, h.r, fill)
            fill.color = 0x16FFFFFF
            c.drawCircle(h.x + h.r * 0.12f, h.y + h.r * 0.16f, h.r * 0.78f, fill)
            ink.color = sizeColor(h.size)
            ink.strokeWidth = 0.9f
            Sketch.circle(c, h.x, h.y, h.r + 0.7f, ink, i * 37 + 3 + boil, 0.07f, sc)
        }

        ink.color = WALL
        ink.strokeWidth = World.WALL_T * 2f
        for ((i, wl) in w.walls.withIndex()) {
            Sketch.line(c, wl.x1, wl.y1, wl.x2, wl.y2, ink, i * 41 + 11, 0.5f, sc)
        }

        for (b in w.balls) if (b.state == BallState.HOME) drawBall(c, b, w)
        for (b in w.balls) {
            if (b.state != BallState.HOME) drawBall(c, b, w, if (tugMode) STEEL else 0, !tugMode && ready && w.inDanger(b))
        }

        for (b in bursts) {
            val k = b.t / BURST_T
            ink.color = (b.color and 0xFFFFFF) or (((1f - k) * 230).toInt() shl 24)
            ink.strokeWidth = 0.35f + 0.8f * (1f - k)
            val r = 6f + 9f * k
            Sketch.circle(c, b.x, b.y, r, ink, 700 + boil, 0.08f, sc)
            for (i in 0 until 8) {
                val a = i * 0.7854f + 0.3f
                Sketch.line(
                    c, b.x + cos(a) * (r + 2f), b.y + sin(a) * (r + 2f),
                    b.x + cos(a) * (r + 5f), b.y + sin(a) * (r + 5f), ink, 710 + i, 0.3f, sc,
                )
            }
        }
        c.restore()
    }

    /** Draws one ball in tray units. [danger] marks a ball that is about to cost an out. */
    private fun drawBall(c: Canvas, b: Ball, w: World, tint: Int = 0, danger: Boolean = false) {
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
        ink.color = if (danger) BAD else INK
        ink.strokeWidth = if (danger) 1f else 0.5f
        Sketch.circle(c, b.x, b.y, r, ink, b.id * 17 + 5 + boil, 0.06f, sc)
    }

    private fun drawFloaters(c: Canvas) {
        for (f in floaters) {
            val k = f.t / FLOAT_T
            val x = ox + f.x * sc
            val y = oy + f.y * sc
            val color = (f.color and 0xFFFFFF) or (((1f - k * k) * 255).toInt() shl 24)
            // Pops in a little too big, then settles.
            val size = 6.5f * u * (1f + 0.45f * max(0f, 1f - f.t / 0.14f))
            if (f.flip) {
                c.save()
                c.rotate(180f, x, y)
                txt(c, f.text, x, y - 9f * u * k, size, color)
                c.restore()
            } else {
                txt(c, f.text, x, y - 9f * u * k, size, color)
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

    private fun pauseButton(c: Canvas, cx: Float, cy: Float, bg: Int = CARD) {
        button(c, cx, cy, 10f * u, 9f * u, "II", 77, size = 5f * u, bg = bg) { pause() }
    }

    private fun leaveButton(c: Canvas, cx: Float, cy: Float, bg: Int = CARD) {
        button(c, cx, cy, 10f * u, 9f * u, "<", 78, size = 5f * u, bg = bg) { leaveRound() }
    }

    /** The three star goals in one line, gold for those in [earned]. */
    private fun goalsLine(c: Canvas, cx: Float, cy: Float, earned: Int, par: Float, size: Float) {
        val labels = arrayOf("sorted", "no outs", "under ${clockText(par)}")
        val starW = size * 1.35f
        val gap = size * 1.2f
        text.textSize = size
        var total = gap * (labels.size - 1)
        for (l in labels) total += starW + text.measureText(l)
        var x = cx - total / 2f
        for ((i, l) in labels.withIndex()) {
            fill.color = if (earned and (1 shl i) != 0) STAR else FAINT
            Sketch.star(c, x + size * 0.5f, cy, size * 0.62f, fill)
            txt(c, l, x + starW, cy, size, GREY, Paint.Align.LEFT)
            x += starW + text.measureText(l) + gap
        }
    }

    /** The "tap to start" card shown over a round that has not begun. */
    private fun startCard(c: Canvas, cy: Float, sub: String, flip: Boolean = false) {
        val cx = width / 2f
        val w = 80f * u
        val h = 19f * u
        c.save()
        if (flip) c.rotate(180f, cx, cy)
        tmpRect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        fill.color = (CARD and 0xFFFFFF) or 0xE0000000.toInt()
        c.drawRoundRect(tmpRect, 2f * u, 2f * u, fill)
        ink.color = INK
        ink.strokeWidth = 0.6f * u
        Sketch.rect(c, tmpRect.left, tmpRect.top, tmpRect.right, tmpRect.bottom, ink, 960 + boil, 1f * u)
        val pulse = 1f + 0.04f * sin(clock * 5.0).toFloat()
        txt(c, "Tap to start", cx, cy - 3.6f * u, 7.4f * u * pulse, INK)
        txt(c, sub, cx, cy + 4.8f * u, 3.7f * u, if (tooSteep) BAD else GREY)
        c.restore()
    }

    private val levelNote: String
        get() = when {
            tooSteep -> "Too steep. Hold it flatter, then tap."
            tilt.available -> "However you hold the phone now is level."
            else -> "Then drag a finger to tilt."
        }

    /** Picks the height on the tray where a start card hides the fewest balls and holes. */
    private fun quietBand(w: World): Float {
        var best = 0.5f
        var bestCount = Int.MAX_VALUE
        val reach = 9.5f * u / sc
        for (f in floatArrayOf(0.5f, 0.3f, 0.7f, 0.16f, 0.84f)) {
            val y = w.h * f
            val count = w.balls.count { abs(it.y - y) < reach + it.r } * 2 + w.holes.count { abs(it.y - y) < reach + it.r }
            if (count < bestCount) {
                bestCount = count
                best = f
            }
        }
        return oy + w.h * sc * best
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
        txt(c, "Paused", cx, t + 10.5f * u, 8f * u, INK)
        if (tooSteep) txt(c, "Too steep. Hold the phone a bit flatter.", cx, t + 18.6f * u, 3.6f * u, BAD)
        var y = t + 28f * u
        button(c, cx, y, 60f * u, 11f * u, "Resume", 910) { paused = false }
        y += 15f * u
        if (showCalibrate) {
            button(c, cx, y, 60f * u, 11f * u, "Set level here", 911, size = 4.8f * u) {
                if (tilt.calibrate()) {
                    tooSteep = false
                    paused = false
                } else {
                    tooSteep = true
                    buzz(HapticFeedbackConstants.LONG_PRESS)
                }
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
        val pu = width / 100f
        val aspect = height / width.toFloat()
        val mw = menuWorld?.takeIf { abs(it.h - Levels.W * aspect) < 0.5f }
            ?: Levels.menuWorld(Levels.W * aspect).also { menuWorld = it }
        ox = 0f
        oy = 0f
        sc = pu
        c.save()
        c.scale(sc, sc)
        for (b in mw.balls) drawBall(c, b, mw)
        c.restore()
        val cx = width / 2f
        val top = safeTop + height * 0.16f
        txt(c, "Tilt & Sort", cx, top, 14f * u, INK)
        txt(c, "a paper marble game", cx, top + 12f * u, 4.6f * u, GREY)
        var y = max(height * 0.42f, top + 27f * u)
        val bw = 70f * u
        val bh = 13f * u
        button(c, cx, y, bw, bh, "Levels", 1) { begin(Screen.LEVELS) }
        y += 17f * u
        val best = prefs.rushBest
        button(c, cx, y, bw, bh, if (best > 0) "Rush  (best $best)" else "Rush", 2) { startRush() }
        y += 17f * u
        // Tug needs two people tilting one phone; without a tilt sensor there is nothing to play.
        if (tilt.available) {
            button(c, cx, y, bw, bh, "2 Players: Tug", 3) { startTug() }
            y += 17f * u
        }
        button(c, cx, y, bw, bh, "How to play", 4) { begin(Screen.HOWTO) }
        y += 16f * u
        val soundLabel = if (sfx.enabled) "Sound: on" else "Sound: off"
        val toggleSound = {
            sfx.enabled = !sfx.enabled
            prefs.sound = sfx.enabled
        }
        if (tilt.available) {
            button(c, cx - 18f * u, y, 34f * u, 9.5f * u, soundLabel, 5, size = 4f * u, action = toggleSound)
            button(c, cx + 18f * u, y, 34f * u, 9.5f * u, "Tilt: ${Tilt.GAIN_NAMES[prefs.sens]}", 6, size = 4f * u) {
                val next = (prefs.sens + 1) % Tilt.GAINS.size
                prefs.sens = next
                tilt.gain = Tilt.GAINS[next]
            }
        } else {
            button(c, cx, y, 44f * u, 9.5f * u, soundLabel, 5, size = 4.2f * u, action = toggleSound)
            txt(c, "No tilt sensor found: drag to tilt.", cx, y + 10.5f * u, 3.8f * u, BAD)
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
                val goals = prefs.goals(n)
                for (s in 0 until 3) {
                    fill.color = if (goals and (1 shl s) != 0) STAR else FAINT
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
        val left = (width - 100f * u) / 2f + 13f * u
        var y = top + 14f * u
        for (l in HOW_TO) {
            if (l.startsWith("#")) {
                txt(c, l.substring(1), left, y, 5.4f * u, BLUE, Paint.Align.LEFT)
            } else if (l.isNotEmpty()) {
                txt(c, l, left, y, 4.2f * u, INK, Paint.Align.LEFT)
            }
            y += if (l.isEmpty()) 3f * u else 6.2f * u
        }
    }

    private fun drawSolo(c: Canvas) {
        val g = solo ?: return
        val hudY = safeTop + 7.5f * u
        fitWorld(g.world, safeTop + 14f * u, height - 15f * u)
        drawWorld(c, g.world)
        drawFloaters(c)
        txt(c, "Level ${g.level}", 5f * u, hudY, 5.6f * u, INK, Paint.Align.LEFT)
        txt(c, clockText(g.time), 43f * u, hudY, 5.2f * u, if (g.time > g.par) LATE else GREY)
        drawOuts(c, 56f * u, hudY, g.outs)
        // Under the tray: the level's hint, or else what the three stars are for.
        val hint = g.hint
        if (hint != null) {
            val parts = hint.split("\n")
            var y = height - 10.5f * u + (2 - parts.size) * 3f * u
            for (p in parts) {
                txt(c, p, width / 2f, y, 4.3f * u, INK)
                y += 6f * u
            }
        } else {
            goalsLine(c, width / 2f, height - 7.5f * u, prefs.goals(g.level), g.par, 3.8f * u)
        }
        when {
            g.won && resultDue -> soloWonPanel(c, g)
            g.lost && resultDue -> {
                val t = panel(c, 52f * u)
                val cx = width / 2f
                txt(c, "Three outs!", cx, t + 11f * u, 8f * u, BAD)
                button(c, cx, t + 27f * u, 60f * u, 11f * u, "Try again", 930) { startSolo(g.level) }
                button(c, cx, t + 42f * u, 60f * u, 11f * u, "Levels", 931) { begin(Screen.LEVELS) }
            }
            g.over -> {}
            paused -> pausePanel(c, { startSolo(g.level) }, tilt.available)
            !ready -> {
                startCard(c, quietBand(g.world), levelNote)
                leaveButton(c, width - 8f * u, hudY)
            }
            else -> pauseButton(c, width - 8f * u, hudY)
        }
    }

    private fun soloWonPanel(c: Canvas, g: SoloGame) {
        val last = g.level >= Levels.COUNT
        val t = panel(c, 90f * u)
        val cx = width / 2f
        txt(c, if (last) "All levels done!" else "Sorted!", cx, t + 10.5f * u, 8f * u, GOOD)
        // One row per star; they are revealed one after another.
        val labels = arrayOf("Sorted", "No outs", "Under ${clockText(g.par)}")
        val since = (clock - overAt - RESULT_DELAY).toFloat()
        for (i in 0 until 3) {
            val y = t + (24f + i * 9.5f) * u
            val shown = i < rowsShown
            val met = shown && g.goals and (1 shl i) != 0
            val pop = if (shown) 1f + 0.6f * max(0f, 1f - (since - (i + 1) * STAR_GAP) / 0.16f) else 1f
            fill.color = if (met) STAR else FAINT
            Sketch.star(c, cx - 21f * u, y, 3.6f * u * pop, fill)
            txt(c, labels[i], cx - 14f * u, y, 5f * u, if (met) INK else 0xFFB4B0A4.toInt(), Paint.Align.LEFT)
        }
        val best = prefs.bestTime(g.level)
        val bestNote = when {
            fasterThanBefore -> "New best!"
            best > 0f && best < g.time - 0.05f -> "Best ${clockText(best, tenths = true)}"
            else -> "Outs ${g.outs}"
        }
        txt(c, "Time ${clockText(g.time, tenths = true)}   $bestNote", cx, t + 54f * u, 4.4f * u, if (fasterThanBefore) GOOD else INK)
        if (!last) {
            button(c, cx, t + 66f * u, 60f * u, 11f * u, "Next level", 920) { startSolo(g.level + 1) }
        } else {
            button(c, cx, t + 66f * u, 60f * u, 11f * u, "Play Rush", 920) { startRush() }
        }
        button(c, cx - 16f * u, t + 80f * u, 28f * u, 10f * u, "Again", 921, size = 4.6f * u) { startSolo(g.level) }
        button(c, cx + 16f * u, t + 80f * u, 28f * u, 10f * u, "Levels", 922, size = 4.6f * u) { begin(Screen.LEVELS) }
    }

    private fun drawRush(c: Canvas) {
        val g = rush ?: return
        val hudY = safeTop + 7.5f * u
        fitWorld(g.world, safeTop + 14f * u, height - 3f * u)
        drawWorld(c, g.world)
        drawFloaters(c)
        txt(c, "${g.score}", 5f * u, hudY, 7f * u, INK, Paint.Align.LEFT)
        // The chain multiplier, with a line under it that shrinks as the chain's timer runs down.
        val m = g.multiplier
        txt(c, "x$m", 43f * u, hudY - 0.9f * u, 5.6f * u * (1f + 0.5f * multPulse / PULSE_T), if (m > 1) GOOD else GREY)
        if (g.chain > 0) {
            val half = 6.5f * u * (g.chainT / g.chainWindow).coerceIn(0f, 1f)
            ink.color = GOOD
            ink.strokeWidth = 0.8f * u
            c.drawLine(43f * u - half, hudY + 4.3f * u, 43f * u + half, hudY + 4.3f * u, ink)
        }
        drawOuts(c, 56f * u, hudY, g.outs)
        when {
            g.over && resultDue -> {
                val t = panel(c, 66f * u)
                val cx = width / 2f
                txt(c, "Three outs!", cx, t + 11f * u, 8f * u, BAD)
                txt(c, "Score ${g.score}", cx, t + 24f * u, 7f * u, INK)
                txt(c, if (newBest) "New best!" else "Best ${prefs.rushBest}", cx, t + 33f * u, 4.6f * u, if (newBest) GOOD else GREY)
                button(c, cx, t + 44f * u, 60f * u, 11f * u, "Play again", 940) { startRush() }
                button(c, cx, t + 58f * u, 60f * u, 11f * u, "Menu", 941) { begin(Screen.MENU) }
            }
            g.over -> {}
            paused -> pausePanel(c, { startRush() }, tilt.available)
            !ready -> {
                // Between the top and middle rows of holes.
                startCard(c, oy + g.world.h * sc * 0.35f, levelNote)
                leaveButton(c, width - 8f * u, hudY)
            }
            else -> pauseButton(c, width - 8f * u, hudY)
        }
    }

    private fun drawTug(c: Canvas) {
        val g = tug ?: return
        fitWorld(g.world, safeTop + 3f * u, height - 3f * u)
        drawWorld(c, g.world, tugMode = true)
        val cx = width / 2f
        val trayH = g.world.h * sc
        val midY = oy + trayH / 2f
        tugMidY = midY
        // Each player's score and shove meter sit in their own half, facing them.
        for (p in 0..1) {
            val y = oy + trayH * (if (p == 0) 0.70f else 0.30f)
            val labelY = oy + trayH * (if (p == 0) 0.92f else 0.08f)
            val charge = 1f - g.cooldown[p] / TugGame.SHOVE_COOLDOWN
            val color = PLAYER[p] and 0xFFFFFF
            c.save()
            if (p == 1) c.rotate(180f, cx, y)
            txt(c, "${g.score[p]}", cx, y, 20f * u, color or 0x70000000)
            c.restore()
            c.save()
            if (p == 1) c.rotate(180f, cx, labelY)
            txt(c, "tap to shove", cx, labelY, 4.2f * u, color or (if (charge >= 1f) 0xD0000000.toInt() else 0x50000000))
            if (charge < 1f) {
                // The shove recharging.
                ink.color = color or 0xB0000000.toInt()
                ink.strokeWidth = 0.8f * u
                val half = 12f * u * charge
                c.drawLine(cx - half, labelY + 4f * u, cx + half, labelY + 4f * u, ink)
            }
            c.restore()
        }
        drawFloaters(c)
        val seeThrough = (CARD and 0xFFFFFF) or 0x50000000
        when {
            g.winner >= 0 && resultDue -> {
                val t = panel(c, 44f * u, midY)
                val name = if (g.winner == 0) "Blue wins!" else "Orange wins!"
                txt(c, name, cx, t + 34f * u, 8f * u, PLAYER[g.winner])
                c.save()
                c.rotate(180f, cx, t + 10f * u)
                txt(c, name, cx, t + 10f * u, 8f * u, PLAYER[g.winner])
                c.restore()
                button(c, cx - 19f * u, t + 22f * u, 34f * u, 10f * u, "Rematch", 950, size = 4.6f * u) { startTug() }
                button(c, cx + 19f * u, t + 22f * u, 34f * u, 10f * u, "Menu", 951, size = 4.6f * u) { begin(Screen.MENU) }
            }
            g.winner >= 0 -> {}
            paused -> pausePanel(c, { startTug() }, false)
            !ready -> {
                // One card facing each player, clear of the marbles waiting on the centre line.
                startCard(c, oy + trayH * 0.72f, "Hold the phone flat between you.")
                startCard(c, oy + trayH * 0.28f, "Hold the phone flat between you.", flip = true)
                leaveButton(c, width - 6.5f * u, midY, seeThrough)
            }
            // See-through, so a marble resting against the rail behind it stays visible.
            else -> pauseButton(c, width - 6.5f * u, midY, seeThrough)
        }
    }

    companion object {
        const val STEP = 1f / 120f
        const val FLOAT_T = 0.9f
        const val BURST_T = 0.45f
        const val SHAKE_T = 0.25f
        const val PULSE_T = 0.22f

        /** Pause between the end of a round and its result panel, so the last sink or out can land. */
        const val RESULT_DELAY = 0.6f
        const val STAR_GAP = 0.28f

        /** After a screen change, taps are ignored this long, so a double tap cannot press a button it never saw. */
        const val TAP_GUARD = 0.25f

        /** Longer guard for panels that appear under fingers already on the screen. */
        const val PANEL_GUARD = 0.6f

        /** How long Tug's shoves take to charge at the start of a match. */
        const val TUG_KICKOFF = 0.6f

        /** Semitones of a major scale. */
        private val SCALE = intArrayOf(0, 2, 4, 5, 7, 9, 11, 12)

        private val HOW_TO = listOf(
            "#Levels",
            "Tilt the phone to roll the balls.",
            "Drop each ball in the hole of its",
            "own size and colour.",
            "A small ball in a bigger hole is OUT.",
            "Rolling off a dashed edge is OUT.",
            "Three outs and the level is lost.",
            "Big balls roll over small holes.",
            "Stars: sort them all, have no outs,",
            "and beat the clock.",
            "",
            "#Rush",
            "Balls keep coming. Sink them quickly:",
            "the chain multiplier runs down.",
            "Ten in a row erases an out.",
            "",
            "#2 Players: Tug",
            "Hold the phone flat between you and",
            "both tilt it! Roll marbles out of",
            "your rival's end to score.",
            "Tap your half to shove. First to ${TugGame.WIN_SCORE}.",
            "",
            "#Tip",
            "However you hold the phone when you",
            "tap to start counts as level.",
        )

        val PAPER = 0xFFFBF7EA.toInt()
        val TRAY = 0xFFFFFDF6.toInt()
        val CARD = 0xFFFFFDF6.toInt()
        val RULE = 0xFFC9DDEE.toInt()
        val MARGIN = 0xFFEDB4B4.toInt()
        val INK = 0xFF2B2B33.toInt()
        val GREY = 0xFF6A6A75.toInt()
        val FAINT = 0xFFD9D4C4.toInt()
        val LATE = 0xFFC0705F.toInt()
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
