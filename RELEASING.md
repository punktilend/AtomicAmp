# Releasing

Debug builds are what has been used for the head unit so far: `assembleDebug` signs with the
shared debug key and installs over any previous debug build. That is fine for sideloading onto
your own unit and needs nothing from this document.

Everything below is for a build other people install.

## 1. Create a signing key (once, and never lose it)

The upload key identifies the app for its entire life. If it is lost, an app already on Play
cannot be updated under the same listing without Google's key-reset process, and a sideloaded app
cannot be updated at all — users have to uninstall first, which deletes their library.

Back it up somewhere that is not this machine.

```bash
keytool -genkeypair -v -keystore atomicamp-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias atomicamp
```

Put it outside the repo, or at the repo root where `.gitignore` already excludes `*.jks`.

## 2. Point the build at it

Create `keystore.properties` at the repo root. It is gitignored, and the build reads it only if
it exists — so a fresh clone still builds, just unsigned.

```properties
storeFile=atomicamp-release.jks
storePassword=<the store password you chose>
keyAlias=atomicamp
keyPassword=<the key password you chose>
```

## 3. Build

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleRelease
```

For Play, `bundleRelease` produces the `.aab` that Play wants instead of an APK. For sideloading
or GitHub Releases, the APK from `assembleRelease` is the right artifact.

**Bump `versionCode` in `app/build.gradle.kts` for every build you hand out.** Android refuses to
install an update whose `versionCode` is not higher, and the failure looks like a corrupt download
rather than a version conflict.

## 4. Check the release build actually runs

Minification is on, and R8 breakage does not show up at compile time — it shows up as a missing
class the first time a code path runs. Media3 in particular loads decoders by name.

An unsigned release APK cannot be installed, so sign one with the debug key to test it:

```bash
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android --out release-test.apk app/build/outputs/apk/release/app-release-unsigned.apk
```

Then install it and actually **play a track** — that is the path that exercises Media3.

## Before publishing anywhere

- No privacy policy exists yet. Play requires a URL for one given the storage permission.
- `resumeOnBoot` defaults to on. Correct for a head unit, wrong for a phone; decide deliberately.
- The filesystem fallback relies on `requestLegacyExternalStorage`, which **Android 11 and later
  ignore**. It works on the ATOTO because that unit is API 29. On a modern phone only the SAF path
  functions, which is fine, but do not describe folder scanning as a general feature.
- The app has been run on one head unit and an emulator shaped like it. Portrait and phone layouts
  are unverified.

## A note on where this goes

The app is GPL-3.0. That is a natural fit for F-Droid and a long-standing point of friction with
Google Play's terms. F-Droid also reaches the people most likely to own hardware with the same
defect this app is built around — a head unit with no document picker. Play reaches a much larger
audience that will compare it to Poweramp on features it does not have.
