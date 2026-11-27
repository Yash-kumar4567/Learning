package com.papertilt.game

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Reads the accelerometer and exposes the tilt as a fraction of g on each screen axis:
 * x grows when the right edge of the screen is lowered, y when the bottom edge is.
 */
class Tilt(private val context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val available: Boolean get() = sensor != null

    /** The player's sensitivity setting: multiplies the tilt before it is limited. */
    var gain = 1f

    private var rawX = 0f
    private var rawY = 0f
    private var calX = 0f
    private var calY = 0f
    private var flatCalX = 0f
    private var flatCalY = 0f
    private var rotation = Surface.ROTATION_0
    private var untilRotationCheck = 0

    /** Tilt relative to the calibrated rest position. */
    val x: Float get() = shape(rawX - calX)
    val y: Float get() = shape(rawY - calY)

    /** Tilt relative to lying flat (used by the two-player mode). */
    val flatX: Float get() = shape(rawX - flatCalX)
    val flatY: Float get() = shape(rawY - flatCalY)

    /** Nothing inside the dead zone, then a smooth start from zero so there is no jump at its edge. */
    private fun shape(v: Float): Float {
        val past = abs(v) - DEAD_ZONE
        if (past <= 0f) return 0f
        return (if (v < 0f) -past else past).times(gain).coerceIn(-MAX_TILT, MAX_TILT)
    }

    fun start() {
        untilRotationCheck = 0
        sensor?.let { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        manager?.unregisterListener(this)
    }

    /**
     * Treat the way the phone is held right now as "level". Returns false, changing nothing,
     * when it is held too upright: past about 50 degrees there is too little range left to steer.
     */
    fun calibrate(): Boolean {
        if (hypot(rawX, rawY) >= MAX_REST) return false
        calX = rawX
        calY = rawY
        return true
    }

    /**
     * Two-player mode: take a phone that is lying nearly flat as exactly flat, so a sloping
     * table or a camera bump does not favour one seat. A phone held at an angle is left alone.
     */
    fun zeroFlat() {
        val nearlyFlat = hypot(rawX, rawY) < FLAT_REST
        flatCalX = if (nearlyFlat) rawX else 0f
        flatCalY = if (nearlyFlat) rawY else 0f
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (untilRotationCheck-- <= 0) {
            untilRotationCheck = 50
            rotation = displayRotation()
        }
        // The sensor's axes are fixed to the device; turn them to match how the screen is shown.
        val ax = e.values[0]
        val ay = e.values[1]
        val sx: Float
        val sy: Float
        when (rotation) {
            Surface.ROTATION_90 -> {
                sx = -ay
                sy = ax
            }
            Surface.ROTATION_180 -> {
                sx = -ax
                sy = -ay
            }
            Surface.ROTATION_270 -> {
                sx = ay
                sy = -ax
            }
            else -> {
                sx = ax
                sy = ay
            }
        }
        // Lowering the right edge makes sx negative; lowering the top edge makes sy negative.
        val nx = -sx / SensorManager.GRAVITY_EARTH
        val ny = sy / SensorManager.GRAVITY_EARTH
        rawX += (nx - rawX) * SMOOTH
        rawY += (ny - rawY) * SMOOTH
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int {
        // An activity knows its own display; anything else falls back to the default one.
        val own = try {
            if (Build.VERSION.SDK_INT >= 30) context.display else null
        } catch (e: Exception) {
            null
        }
        val display = own ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
        return display?.rotation ?: Surface.ROTATION_0
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        const val DEAD_ZONE = 0.02f
        const val MAX_TILT = 0.5f
        const val SMOOTH = 0.3f
        const val MAX_REST = 0.77f
        const val FLAT_REST = 0.15f

        /** Sensitivity settings: low, normal, high. */
        val GAINS = floatArrayOf(0.65f, 1f, 1.5f)
        val GAIN_NAMES = arrayOf("low", "normal", "high")
    }
}

enum class Snd { TAP, TICK, GOOD, BAD, WIN, LOSE, POP }

/** Tiny synthesised sound effects, so the app ships no audio files. */
class Sfx {
    var enabled = true
    private val tracks = HashMap<Snd, AudioTrack>()

    init {
        try {
            tracks[Snd.TAP] = build(tone(700f, 500f, 50, 0.35f))
            tracks[Snd.TICK] = build(tone(260f, 140f, 45, 0.5f))
            tracks[Snd.POP] = build(tone(300f, 620f, 90, 0.35f))
            tracks[Snd.GOOD] = build(tone(520f, 880f, 110, 0.5f) + tone(1040f, 1040f, 160, 0.45f))
            tracks[Snd.BAD] = build(tone(220f, 90f, 320, 0.6f, buzz = true))
            tracks[Snd.WIN] = build(
                tone(523f, 523f, 110, 0.5f) + tone(659f, 659f, 110, 0.5f) +
                    tone(784f, 784f, 110, 0.5f) + tone(1047f, 1047f, 320, 0.5f),
            )
            tracks[Snd.LOSE] = build(tone(392f, 392f, 160, 0.5f) + tone(311f, 311f, 160, 0.5f) + tone(233f, 200f, 420, 0.5f))
        } catch (e: Exception) {
            tracks.clear()
        }
    }

    private fun tone(f0: Float, f1: Float, ms: Int, vol: Float, buzz: Boolean = false): ShortArray {
        val n = RATE * ms / 1000
        val out = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val k = i / n.toFloat()
            phase += 2 * PI * (f0 + (f1 - f0) * k) / RATE
            var s = sin(phase).toFloat()
            if (buzz) s = if (s > 0f) 0.6f else -0.6f
            val attack = (i / (RATE * 0.004f)).coerceAtMost(1f)
            val env = attack * exp(-3.5f * k)
            out[i] = (s * env * vol * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    @Suppress("DEPRECATION")
    private fun build(pcm: ShortArray): AudioTrack {
        val t = AudioTrack(
            AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT, pcm.size * 2, AudioTrack.MODE_STATIC,
        )
        t.write(pcm, 0, pcm.size)
        return t
    }

    /** [rate] above 1 plays the sound faster and higher (2 is an octave up). */
    fun play(s: Snd, rate: Float = 1f) {
        if (!enabled) return
        val t = tracks[s] ?: return
        try {
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.playbackRate = (RATE * rate.coerceIn(0.5f, 2f)).toInt()
            t.play()
        } catch (e: Exception) {
            // Sound is a nicety; never let it take the game down.
        }
    }

    fun release() {
        for (t in tracks.values) {
            try {
                t.release()
            } catch (e: Exception) {
            }
        }
        tracks.clear()
    }

    companion object {
        const val RATE = 22050
    }
}

/** Saved progress. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("tiltsort", Context.MODE_PRIVATE)

    var unlocked: Int
        get() = sp.getInt("unlocked", 1)
        set(v) = sp.edit().putInt("unlocked", v).apply()

    var rushBest: Int
        get() = sp.getInt("rushBest", 0)
        set(v) = sp.edit().putInt("rushBest", v).apply()

    var sound: Boolean
        get() = sp.getBoolean("sound", true)
        set(v) = sp.edit().putBoolean("sound", v).apply()

    /** Tilt sensitivity: an index into [Tilt.GAINS]. */
    var sens: Int
        get() = sp.getInt("sens", 1).coerceIn(0, Tilt.GAINS.size - 1)
        set(v) = sp.edit().putInt("sens", v).apply()

    /** The goals (see SoloGame.goals) ever reached on a level, one bit each. */
    fun goals(level: Int): Int = sp.getInt("goals_$level", 0)

    fun stars(level: Int): Int = goals(level).countOneBits()

    /** Fastest win in seconds, or 0 when the level has not been won. */
    fun bestTime(level: Int): Float = sp.getFloat("best_$level", 0f)

    /** Records a win; returns true when [time] is a new best for the level. */
    fun recordWin(level: Int, goals: Int, time: Float): Boolean {
        val old = bestTime(level)
        val faster = old <= 0f || time < old
        val e = sp.edit().putInt("goals_$level", goals(level) or goals)
        if (faster) e.putFloat("best_$level", time)
        e.apply()
        return faster && old > 0f
    }
}
