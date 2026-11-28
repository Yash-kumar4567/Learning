# Play Store listing

Text to paste into Play Console under **Grow > Store presence > Main store listing**.
The images are in `graphics/`.

## App name (30 characters max)

Tilt & Sort: Paper Marbles

## Short description (80 characters max)

Tilt your phone, roll the marbles, and drop each one into the hole of its size.

## Full description (4000 characters max)

Tilt & Sort is a marble game drawn like a doodle on notebook paper. There are no buttons to press: you tilt your phone and the marbles roll.

Every marble has a hole of its own size and colour. Get them all home and the page is sorted. But every marble feels the same tilt, so moving one moves them all, and a small marble will happily drop into a bigger hole that is not its own. Roll off an open edge of the paper and that is an out too.

LEVELS
• 40 levels, each one harder than the last: more marbles, fewer rails, faster and more slippery paper, and a clock that keeps getting tighter.
• Pencil walls to lean on, sand that slows you down, and holes that wander about.
• Three stars a level: sort them all, lose none, and beat the clock.

RUSH
• Nine holes and marbles that never stop arriving.
• Sink them quickly to build a chain multiplier up to x5. Wait too long and it runs down.
• Ten in a row wins back a lost marble. How long can you last?

TWO PLAYERS: TUG
• One phone held flat between two people, both tilting it at once.
• Roll the marbles out of your rival's end to score. Tap your half to shove them away.
• First to seven wins.

MADE TO BE SIMPLE
• Hold the phone however is comfortable: the way you hold it when you tap to start counts as level.
• Works offline. No account, no ads, no in-app purchases.
• Collects no data at all.
• Under 1 MB.

## Graphics

| Asset | File | Play requirement |
| --- | --- | --- |
| App icon | `graphics/icon-512.png` | 512 x 512 PNG |
| Feature graphic | `graphics/feature-1024x500.png` | 1024 x 500 |
| Phone screenshots | `graphics/phone-1-menu.png` to `phone-7-levels.png` | 2 to 8, 9:16, 1080 x 1920 |

They are drawn by the game's own code. To redo them after a visual change:

    STORE=1 ./gradlew cleanTestDebugUnitTest testDebugUnitTest --tests '*StoreAssets*'
    cp app/build/store/*.png store/graphics/

## Categorisation

- App or game: **Game**
- Category: **Puzzle** (Casual also fits)
- Tags: Puzzle, Casual, Offline, Single player, Local multiplayer
- Contact email: yours (it is shown publicly on the listing)
