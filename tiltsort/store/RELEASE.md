# Releasing Tilt & Sort on Google Play

What is already done in this repository, and what only the account owner can do.

## Already done

- Targets Android 16 (API 36), which Play requires of new apps; runs on Android 7.0 and up.
- Release build is shrunk with R8 and produces an Android App Bundle (`.aab`).
- Signing is wired up: put the upload key's details in `keystore.properties` and the bundle comes out signed.
- No permissions, no network use, no ads, no data collected.
- Store text (`listing.md`), images (`graphics/`) and a privacy policy (`privacy-policy.md`).

## 1. Create the upload key (once)

This key proves an upload comes from you. Keep the file and its password somewhere safe
and private (a password manager). Never commit it; `.gitignore` already excludes it.

    cd tiltsort
    keytool -genkeypair -v -keystore upload.jks -alias upload \
        -keyalg RSA -keysize 2048 -validity 10000

Then copy `keystore.properties.example` to `keystore.properties` and fill in the two
passwords you chose.

With Play App Signing (the default) Google holds the real app signing key, so a lost
upload key can be replaced through Play Console support. It is still a credential.

## 2. Build the bundle

    export ANDROID_HOME=/path/to/android-sdk     # needs platform 36
    ./gradlew testDebugUnitTest                   # everything should pass
    ./gradlew bundleRelease

The file to upload is `app/build/outputs/bundle/release/app-release.aab`.

For every later update, raise `versionCode` (and `versionName`) in `app/build.gradle.kts`
first: Play refuses a bundle whose version code it has already seen.

## 3. Play Console

1. **Account.** Register at play.google.com/console (one-time 25 USD fee, identity check).
2. **Create app.** Name "Tilt & Sort: Paper Marbles", default language, type Game, Free.
   The package name `com.papertilt.game` becomes permanent with the first upload.
3. **Store listing.** Paste the text from `listing.md` and upload the images from `graphics/`.
4. **Privacy policy.** Put `privacy-policy.md` (with your contact email filled in) at a
   public web address, for example a GitHub Pages site or a public gist, and give Play that URL.
5. **App content** forms:
   - Data safety: the app collects no data and shares no data.
   - Ads: contains no ads.
   - App access: all features available without special access.
   - Content rating: fill in the questionnaire; nothing in the game is violent, sexual or
     gambling, so it should come out at the lowest rating.
   - Target audience: choosing 13 and over keeps the app out of the Families programme,
     which has extra requirements. Choose younger ages only if you want those.
   - Government apps, financial features, health: none.
6. **Testing first.** Personal developer accounts created since November 2023 must run a
   closed test before they can publish to production: currently at least 12 testers opted
   in for 14 days in a row. Play Console shows the exact requirement for your account.
   Upload the bundle to an internal or closed testing track, add testers by email, and
   play it on real phones.
7. **Production.** When testing is done, apply for production access, promote the release
   and send it for review. Review usually takes a few days.

## Before pressing publish

The game has only ever been played on one phone. During the closed test, check on a few
different devices: tilt direction, sound, vibration, the back gesture, and a phone with a
camera cut-out. Check a tablet too, since Android 16 ignores the portrait lock on large
screens.
