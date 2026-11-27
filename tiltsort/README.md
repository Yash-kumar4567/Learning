# Tilt & Sort

A small Android marble game drawn like a doodle on notebook paper. You tilt the
phone to roll balls around a tray and drop each one into the hole of its own
size. It is a native Kotlin app that draws on a plain `Canvas`: no game engine,
no image or sound files, and the debug APK is under 1 MB.

## How it plays

- Balls and holes come in three sizes, and each size has a colour (small red,
  medium blue, large green). A hole's ring shows which ball it wants.
- A ball drops into any hole at least its own size. The right size is a score.
  A smaller ball lost in a bigger hole is an **out**.
- A solid pencil line on the tray's edge is a rail. A dashed edge is open, and
  a ball that rolls off it is an **out**.
- Every ball feels the same tilt, so moving one moves them all.

### Modes

| Mode | What it is |
| --- | --- |
| **Levels** | 40 levels. Three outs lose the level; fewer outs earn more stars. Later levels add pencil walls, sand that slows balls, holes that slide about, and fewer rails. |
| **Rush** | Nine holes in a 3 x 3 grid and balls that keep arriving. Sinking balls in a row raises the multiplier up to x5. Three outs end the run. |
| **2 Players: Tug** | The phone lies flat between two players and both tilt it. A marble that leaves through your rival's end is your point; tapping your half shoves the marbles away from you. First to 7. |

Pause and choose **Set level here** to play with the phone held at an angle
instead of flat. On a device with no accelerometer you drag a finger to tilt.

## Install

Build the debug APK (below), copy `app/build/outputs/apk/debug/app-debug.apk`
to the phone and open it. Android will ask you to allow installing from that
source. It needs Android 7.0 or newer.

## Build

You need JDK 17 or newer and the Android SDK (platform 35). Either open this
folder in Android Studio, or from a terminal:

```sh
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # rules, all 40 levels, and screenshots
```

`assembleRelease` produces an unsigned APK; sign it with your own key before
publishing.

## Tests

- `CoreTest` checks the rules without Android: sinking, wrong holes, open
  edges and rails, that every level is well formed and calm while the phone is
  flat, and that Rush and Tug score and finish.
- `ScreenshotTest` draws every screen with Robolectric and saves PNGs to
  `app/build/screenshots/`, so layout changes can be checked without a phone.

## Layout

```
app/src/main/java/com/papertilt/game/
  core/World.kt     physics: rolling, walls, holes, collisions (no Android code)
  core/Levels.kt    4 hand-made tutorial levels and the generator for the rest
  core/Modes.kt     rules for Levels, Rush and Tug
  GameView.kt       every screen, the game loop and touch input
  Sketch.kt         the wobbly hand-drawn strokes
  Platform.kt       tilt sensor, synthesised sounds, saved progress
  MainActivity.kt   full-screen setup and lifecycle
```

The feel of the game is set by the constants at the bottom of `World.kt`
(`ACC` for how hard tilt pushes, `FRICTION`, `MAX_V`) and by `Tilt.SMOOTH` and
`Tilt.DEAD_ZONE` in `Platform.kt`.
