package com.papertilt.game

import android.annotation.SuppressLint
import android.app.Activity
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher

class MainActivity : Activity() {
    private lateinit var tilt: Tilt
    private lateinit var sfx: Sfx
    lateinit var view: GameView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The volume keys should change the game's sound, not the ringer.
        volumeControlStream = AudioManager.STREAM_MUSIC
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val prefs = Prefs(this)
        tilt = Tilt(this)
        tilt.gain = Tilt.GAINS[prefs.sens]
        sfx = Sfx()
        sfx.enabled = prefs.sound
        view = GameView(this, tilt, sfx, prefs)
        setContentView(view)
        hideBars()
        // From Android 13 the system asks for back through this callback, and an app that
        // targets Android 16 no longer gets onBackPressed at all.
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (!view.onBack()) finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        tilt.start()
        hideBars()
    }

    override fun onPause() {
        tilt.stop()
        view.onHostPause()
        super.onPause()
    }

    override fun onDestroy() {
        sfx.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The notification shade and system dialogs take focus without pausing the activity.
        if (hasFocus) hideBars() else view.onHostPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        @Suppress("DEPRECATION")
        super.onMultiWindowModeChanged(isInMultiWindowMode)
        // Entering or leaving split-screen changes what covers the top of the window.
        view.requestApplyInsets()
    }

    // Still how back arrives on Android 7 to 12; newer versions use the callback set in onCreate.
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!view.onBack()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }
}
