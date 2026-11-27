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
  a ball that rolls off it is an **out**. A ball about to cost an out gets a
  red outline.
- Every ball feels the same tilt, so moving one moves them all.
- Every level has one hole per ball and is harder than the one before: tilt
  pushes the balls harder, the paper gets more slippery, the countdown clock
  allows less slack, and later levels end on two outs, then on one.
- Every round waits for a tap. However the phone is held at that moment counts
  as level, so it can be played flat on a palm or tipped towards you.

### Modes

| Mode | What it is |
| --- | --- |
| **Levels** | 40 levels. Running out of outs (three at first, then two, then one) or out of time loses the level. A win earns up to three stars: one for sorting every ball, one for no outs, one for beating the level's par time. Later levels add pencil walls, sand that slows balls, holes that slide about, and fewer rails. |
| **Rush** | Nine holes in a 3 x 3 grid and balls that keep arriving. Each sink adds a link to a chain multiplier (up to x5); the chain loses a link whenever its timer runs out, so waiting costs points. Ten sinks in a row erase an out. Three outs end the run. |
| **2 Players: Tug** | The phone is held flat between two players and both tilt it. A marble that leaves through your rival's end is your point; tapping your half shoves the marbles away from you and stops one at your own edge. First to 7. |

The menu has a **Tilt** setting (low, normal, high) for how strongly the balls
react. Pause and choose **Set level here** to change what counts as level in
the middle of a round. On a device with no accelerometer you drag a finger to
tilt, and Tug is hidden.

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
./gradlew testDebugUnitTest    # rules, screens and screenshots
```

`assembleRelease` produces an unsigned APK; sign it with your own key before
publishing.

## Tests

- `CoreTest` checks the rules without Android: sinking, wrong holes, open
  edges and rails, stars, the Rush chain, Tug scoring and shoves, and that
  every level is well formed and calm while the phone is flat.
- `GameViewTest` drives the real view with Robolectric: tap to start, pausing,
  the guard against stray taps, saved scores, and the tilt sensor under every
  display rotation.
- `ScreenshotTest` draws every screen, at phone, tablet and split-screen
  sizes, and saves PNGs to `app/build/screenshots/`, so layout changes can be
  checked without a phone.
- `SolvabilityProbe` plays all 40 levels with a bot to check that each can be
  won and none is a trap. It takes several minutes, so it only runs when asked:
  `PROBE=1 ./gradlew cleanTestDebugUnitTest testDebugUnitTest --tests '*SolvabilityProbe*'`
  (or `PROBE_LEVELS=22,38` for chosen levels). Results go to
  `app/build/solvability/`.

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
(`ACC` for how hard tilt pushes, `FRICTION` for how soon a ball stops, `MAX_V`)
and by `Tilt.SMOOTH`, `Tilt.DEAD_ZONE` and `Tilt.GAINS` in `Platform.kt`.
Changing the first three changes how hard every level is, so re-run the probe
afterwards.
