package com.papertilt.game

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/** Reads the accelerometer and exposes the tilt as a fraction of g on each screen axis. */
class Tilt(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val available: Boolean get() = sensor != null

    private var rawX = 0f
    private var rawY = 0f
    private var calX = 0f
    private var calY = 0f

    /** Tilt relative to the calibrated rest position. */
    val x: Float get() = shape(rawX - calX)
    val y: Float get() = shape(rawY - calY)

    /** Tilt relative to lying flat, ignoring calibration (used by the two-player mode). */
    val flatX: Float get() = shape(rawX)
    val flatY: Float get() = shape(rawY)

    private fun shape(v: Float): Float = if (abs(v) < DEAD_ZONE) 0f else v.coerceIn(-MAX_TILT, MAX_TILT)

    fun start() {
        sensor?.let { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        manager?.unregisterListener(this)
    }

    /** Treat the way the phone is held right now as "level". */
    fun calibrate() {
        // Past about 50 degrees there is too little range left to steer with.
        if (hypot(rawX, rawY) < 0.77f) {
            calX = rawX
            calY = rawY
        }
    }

    fun resetCalibration() {
        calX = 0f
        calY = 0f
    }

    override fun onSensorChanged(e: SensorEvent) {
        // Lowering the right edge makes x negative; lowering the top edge makes y negative.
        val nx = -e.values[0] / SensorManager.GRAVITY_EARTH
        val ny = e.values[1] / SensorManager.GRAVITY_EARTH
        rawX += (nx - rawX) * SMOOTH
        rawY += (ny - rawY) * SMOOTH
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        const val DEAD_ZONE = 0.012f
        const val MAX_TILT = 0.5f
        const val SMOOTH = 0.3f
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

    fun play(s: Snd) {
        if (!enabled) return
        val t = tracks[s] ?: return
        try {
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
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

    fun stars(level: Int): Int = sp.getInt("stars_$level", 0)

    fun setStars(level: Int, stars: Int) {
        if (stars > stars(level)) sp.edit().putInt("stars_$level", stars).apply()
    }
}
