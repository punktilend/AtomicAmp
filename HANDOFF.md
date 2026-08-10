# AtomicAmp — handoff

Working notes for picking this project up cold. The README covers *what the code does*; this covers
**what was learned, what's unresolved, and what will waste your time if you don't know it.**

- Repo: <https://github.com/punktilend/AtomicAmp> (public, GPL-3.0)
- Local: `C:\Users\adamm\AtomicPowerAmp`
- Branches: `main` (all verified work), `crossfade` (built + verified, deliberately unmerged)

---

## 1. The target device dictates almost every design choice

Not a phone. An **ATOTO S8 head unit in a 2016 Subaru Crosstrek**. Specs read off the device via the
app's own diagnostics screen:

| | |
|---|---|
| OS | **Android 10 (API 29)** |
| Screen | **1280x720 px, density 1.0** → 1280x720 dp, ~1280x660 usable |

Consequences that are easy to get wrong:

- **The bundled `ATOTO_S8` AVD is wrong** — it is 1024x600 / API 36, matching neither. Don't trust
  it. Override a running emulator instead:
  ```bash
  adb shell wm size 1280x720 && adb shell wm density 160
  ```
- **Density 1.0 is a trap.** The panel is ~210 ppi but Android reports 160, so every dp renders
  *physically smaller* than the same dp on a phone. The platform's 48dp minimum touch target lands
  under 6mm. Rows are 76dp and transport buttons 72dp for this reason — don't "tidy" them smaller.
- **API 29 ceiling.** `StorageVolume.getDirectory()` (30) and `POST_NOTIFICATIONS` (33) are both
  guarded. Anything newer must be too.
- The process is killed at **every ignition-off**, which is why EQ, queue, position, shuffle/repeat
  and leveler state are all persisted and restored.

## 2. Unresolved, and it gates real work

**Can the ATOTO's SAF document picker see USB storage?** Unknown. The entire library is built on SAF
folder grants, so if the picker can't reach removable media, `LibraryScanner` needs a
direct-filesystem fallback. Everything else is downstream of this answer.

A USB stick was prepared at `R:\` with `AtomicAmp.apk` + test music. The check is: sideload, tap
**Add folder**, see whether the stick is listed.

**ADB is not available on the unit.** Its AICE UI firmware hides Developer options (the
build-number tap is inert) and exposes no network ADB — confirmed by scanning ports 1–10000 and the
full ephemeral range from the laptop: nothing listening, and 5555 returns an explicit RST. That is
why `diagnostics/DiagnosticsScreen` exists: it reports build/OS, real display metrics, storage
volumes and mount points from inside the app, plus buttons firing
`Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS` and friends, since vendor skins often hide the
settings *entry* while leaving the activity reachable.

Also untested on hardware: whether the unit's **output path** accepts 24-bit/192 kHz. Media3
decodes it fine in software (verified), but head units often cap or resample. Risk is quality, not
silence.

## 3. The user's real library shapes requirements

`K:\Media\Music` — **3,164 FLACs, 61 GB**. Facts established by scanning it, not assuming:

- **0 of 3,150 readable files have ReplayGain tags.** This is why loudness is measured in real time
  rather than read. Don't "add ReplayGain support" — it would do nothing. (14 files didn't parse as
  FLAC at all; worth investigating sometime.)
- **52 cue sheets describing 569 audio tracks.** 47 albums are a single FLAC + cue and are split
  into real tracks. **5 sheets name multiple FILEs** — those albums are already one FLAC per track
  with times restarting at zero, and splitting them would stack every track at 0:00. That
  distinction is `CueSheet.isSingleFile`; keep it.
- All cue sheets are valid UTF-8 and every AUDIO track has an `INDEX 01`, so no fallback handling is
  needed for either.
- Real `cover.jpg` folder art is common, which is why art isn't read from tags alone.

## 4. Architecture, briefly

- **`:engine`** — playback and DSP. `PlaybackService` (Media3 `MediaSessionService`) hosts one
  `ExoPlayer`. Audio processor chain order is deliberate: **equalizer → leveler → fade**. The
  leveler measures the equalized signal because that's what's heard; the fade is last so its
  envelope isn't mistaken for the track getting quieter and corrected away.
- **`:app`** — Room library (independent of `MediaStore`), Compose UI, two destinations plus
  diagnostics.
- **The engine is the source of truth**, not the UI. The service restores persisted state and
  outlives the UI, so `PlayerViewModel` pulls EQ state, queue, and modes *from* the player on
  connect. Assuming defaults in the UI produces sliders that disagree with what's audible.

## 5. Things that cost time — read before debugging

- **Gradle distribution corrupts repeatedly.** Twice now `commons-compress-1.26.1.jar` vanished from
  the extracted dist while all 161 other jars remained, with the `.ok` marker still present.
  Symptom: `Cannot find JAR 'commons-compress...'`. Fix: delete
  `~/.gradle/wrapper/dists/gradle-8.9-bin/<hash>` and let the wrapper refetch. Defender's log is
  clean, so root cause is unknown; a Defender exclusion for `~/.gradle` is the likely permanent fix
  but that's a security setting for the user to make.
- **MSYS mangles paths.** `adb shell` remote paths need `MSYS_NO_PATHCONV=1` (or `//sdcard/...`),
  but that same variable then breaks *local* Windows paths for `adb install` and `ffmpeg`. Set it
  per-command, never globally for a mixed command.
- **`adb push src dst` nests** when `dst` already exists — creates `Music/Artist/Artist/...`. This
  produced a fake "duplicate tracks bug" once. Check the filesystem before blaming the scanner.
- **`install -r` alone is not enough** after a code change; a stale process keeps running the old
  APK. Always `am force-stop` first. This masqueraded as "the prune isn't working".
- **Binary pulls need `exec-out`**, not `shell` — `shell` corrupts them with CRLF translation.
  Pulling the Room DB also needs `library.db-wal`, or it looks empty.
- **Emulator `/data` fills up.** Real FLAC albums are ~230 MB each; installs then fail with
  "not enough space". Use short excerpts (`ffmpeg -t 300`) rather than whole albums.
- **`dumpsys` polling is too slow** to observe short windows. It falsely showed a crossfade
  producing no overlap. Instrument the player directly instead.

## 6. Verification habits that caught real bugs

Screenshots and unit tests each caught things the other couldn't:

- Sizing album art off *width* compiled fine and looked plausible, but pushed the transport buttons
  off a 600dp-tall screen. Only a screenshot caught it.
- `LinearRamp` accumulating a float step drifted to 0.99998 instead of 1.0, so a fade-out never
  became exactly silent. Only a unit test caught it.
- SystemUI couldn't read `file://` artwork in app-private storage — album art was blank in the
  notification and on the lock screen. Only logcat caught it, and the fix needed *both* a
  FileProvider `content://` URI **and** artwork bytes, because the URI alone is not readable by
  another process without a grant.

Verify on the ATOTO geometry, not the AVD default. Full suite: **63 tests**, run with
`./gradlew :app:testDebugUnitTest :engine:testDebugUnitTest`.

Build:
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug
```

## 7. The `crossfade` branch

Complete and verified structurally; **unmerged on purpose**.

Design avoids the obvious trap: rather than a facade re-implementing queue handling, the primary
player keeps owning the timeline and session, and a second player carries only the *tail* of the
outgoing track while the primary advances early. Resume, playlists and notification controls are
structurally untouched.

Verified: with the overlap running the tail reported READY, playing, position advancing, and volume
**0.9546 at 800ms into a 4s fade** — `cos(π/2 × 0.2)` to three decimals, the equal-power curve on
real playback. Also confirmed no regression with crossfade off.

**Not verified: how it sounds.** For a crossfade that is the entire point, which is why it sits on a
branch. Listen before merging.

## 8. What's next

1. **Answer the SAF/USB question** — blocks the most.
2. **Listen to the crossfade branch**, then merge or tune.
3. No instrumented tests exist; the Room migrations (now at v4) are only verified by hand. The 3→4
   migration rebuilds two tables and deserves an automated test.
4. Sleep timer, tag editor, synced lyrics, widgets/skins — real Poweramp features, lower value here.
5. Android Auto and Chromecast are **deliberately deprioritized** — on a device that *is* the head
   unit they buy little.

## 9. Working style that fit this project

Verify claims on the device rather than asserting them; measure rather than estimate (scan
throughput, gain values, track boundaries); and when a measurement contradicts an earlier
conclusion, say so plainly — that happened more than once here and each time the correction
mattered.
