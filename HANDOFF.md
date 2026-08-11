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

- **Use the `ATOTO_S8_A10` AVD.** It is API 29 at 1280x720 / density 160 natively — the unit's real
  numbers, no overrides. The older bundled **`ATOTO_S8` AVD is wrong** (1024x600 / API 36, matching
  neither); if you are stuck on it, override the running emulator instead:
  ```bash
  adb shell wm size 1280x720 && adb shell wm density 160
  ```
  `ATOTO_S8_A10` is a stock AOSP Android 10 image
  (`Android/sdk_phone_x86_64/generic_x86_64:10/QSR1.210820.001`), **not** ATOTO's firmware. It
  matches the *platform*, so API-29 behaviour and dp math are trustworthy. It says nothing about
  the AICE skin, the SAF picker's view of USB, or the audio HAL — those are vendor layers and only
  the car can answer them.
- **Density 1.0 is a trap.** The panel is ~210 ppi but Android reports 160, so every dp renders
  *physically smaller* than the same dp on a phone. The platform's 48dp minimum touch target lands
  under 6mm. Rows are 76dp and transport buttons 72dp for this reason — don't "tidy" them smaller.
- **API 29 ceiling.** `StorageVolume.getDirectory()` (30) and `POST_NOTIFICATIONS` (33) are both
  guarded. Anything newer must be too.
- The process is killed at **every ignition-off**, which is why EQ, queue, position, shuffle/repeat
  and leveler state are all persisted and restored. Persisting them was not enough on its own:
  `apply()` returns before the write reaches disk, and an ignition-off cuts power, so position
  checkpoints `commit()` synchronously. They also write *only* index and position — the periodic
  save used to re-serialise the whole queue to JSON every five seconds, which against thousands of
  tracks is a large write on a timer.
- **The UI is dark with no light variant, on purpose.** A near-white panel at eye level in a dark
  cabin is glare on the glass and ruined dark adaptation. The accent is amber for the reason
  instrument clusters have been for decades: legible at low brightness, little blue light. Type is
  scaled above Material's defaults because density 1.0 makes every dp physically smaller here.

## 2. Answered: the unit has no SAF at all

This section was the project's central unknown for months. It is settled, and the answer was worse
than "the picker can't see USB".

**`com.android.documentsui` is not installed on the ATOTO.** Not disabled, not hidden — absent.
Measured on the device via the Diagnostics screen: `OPEN_DOCUMENT_TREE` and `OPEN_DOCUMENT` both
report `NOTHING HANDLES THIS`, and only `GET_CONTENT` resolves, to Amaze File Manager and Google
Docs. So **SAF folder grants were never possible on the target device**, and the original library
design had no way in. Pressing Add folder didn't fail gracefully, it threw
`ActivityNotFoundException` and killed the app.

What the same screen showed is why this was recoverable:

```
LittleRed  removable=true primary=false state=mounted
[1] /storage/USB1/Android/data/com.atomic.atomicamp/files
/storage/USB1   -> 145 audio file(s), e.g. 01 - Ricky.flac
/storage/usbdisk -> 145 audio file(s)
```

Plain `File` access to the mounted stick works, because `requestLegacyExternalStorage` is honoured
on API 29. So `LibraryScanner` now takes a **`file://` root as well as a SAF tree**, and only the
directory walk differs — tags, album art and cue sheets are read through `Uri` either way, so both
paths share all the extraction logic. Since folder identity is just a string in `MusicFolder.uri`,
storing `file:///storage/USB1/Music` needed **no schema migration**.

Two consequences to keep in mind:

- **A `file://` root has no persistable grant.** Reaching it again after an ignition-off depends on
  `READ_EXTERNAL_STORAGE`, not on a URI permission — don't "fix" `addFolder` by calling
  `takePersistableUriPermission` on it, which throws.
- **`FolderPickerScreen` exists because the device has no picker to borrow.** It lists volume roots
  derived from `getExternalFilesDirs` rather than by listing `/storage`, because `canRead()` is a
  false negative on `/storage/emulated` even where its children are readable — the emulator showed
  an empty list until that was changed.

Both paths are verified. The filesystem path was tested by *disabling* DocumentsUI on the API 29
emulator, which reproduces the unit's condition exactly: browse, scan, tags, art and playback of a
24/192 FLAC all work with SAF absent, and re-enabling it puts the system picker back.

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

- **0 of 3,350 files have ReplayGain tags.** Re-measured across the whole library after it grew from
  the original 3,164, this time with `ffprobe` rather than a hand-written metadata-block reader:
  every file returned tags, none returned a `REPLAYGAIN_*` or `R128_*` key, and nothing failed to
  parse. This is why loudness is measured in real time rather than read. Don't "add ReplayGain
  support" — it would do nothing.
- **The 14 files that "didn't parse as FLAC" are fine — settled, don't re-investigate.** They are
  all of *NOFX — Ribbed*, every track, each a valid FLAC carrying a ~4.5 KB ID3v2 tag *ahead* of
  the `fLaC` magic. Only the PC-side survey script cared, because it looked for the magic at offset
  zero. `MediaMetadataRetriever` — which is how `LibraryScanner` reads every tag — handles the
  prefix transparently: measured on API 29 against the same file with the tag stripped, both give
  identical mime, duration and tags. Nothing in the app checks the magic itself, so the album
  scans and plays normally.
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

Verify on the ATOTO geometry, not the AVD default. Full suite: **63 unit tests**, run with
`./gradlew :app:testDebugUnitTest :engine:testDebugUnitTest`, plus **6 instrumented migration
tests** needing a running emulator:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:connectedDebugAndroidTest
```

The migration tests were themselves checked by breaking the migration on purpose and confirming
they failed. Worth knowing which check catches what: dropping the `ON DELETE CASCADE` failed four
tests, because `runMigrationsAndValidate` compares foreign keys. But copying the tracks table
*without* `metadataInferred` produced a byte-identical schema and was caught only by the assertion
that reads the value back. Schema validation alone would have shipped that data loss.

Build:
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug
```

## 7. The `crossfade` branch

Complete, **merged up to date with `main`**, still **unmerged into `main` on purpose**.

Design avoids the obvious trap: rather than a facade re-implementing queue handling, the primary
player keeps owning the timeline and session, and a second player carries only the *tail* of the
outgoing track while the primary advances early. Resume, playlists and notification controls are
structurally untouched.

Verified: with the overlap running the tail reported READY, playing, position advancing, and volume
**0.9546 at 800ms into a 4s fade** — `cos(π/2 × 0.2)` to three decimals, the equal-power curve on
real playback. Also confirmed no regression with crossfade off.

**Not verified: how it sounds.** For a crossfade that is the entire point, which is why it sits on a
branch. Listen before merging.

`main` has been merged into it (`b9240f0`), so it builds against everything current — dark theme,
filesystem scanner, resume-on-boot, release plumbing — and its own tests bring the suite to 71.
Twelve textual conflicts, all the same shape: each branch had added a feature beside the other, so
both sides stayed. The conflict that mattered was invisible to git — `CrossfadeController` builds
the tail player and was still calling `EngineRenderersFactory` with the signature from before the
leveller joined the chain, which only surfaced as a type error once the merge compiled.

**One caveat to listen for.** The tail player gets its own leveller, deliberately left off. A fresh
one would start at unity and climb ~1 dB/sec while the primary had already settled elsewhere for
that same track, so the audio being faded out would drift. Flat is at least steady. What it does
not fix: with levelling **on** and the primary settled away from unity, the tail starts at a
different level than the track was just playing at. Seeding the tail with the primary's gain is the
real answer and needs a setter `LevelerAudioProcessor` does not have. Both features default off, so
this only bites with both on — try that combination deliberately.

**The listening session is already set up — it needs ears, not setup.** On the `ATOTO_S8_A10`
emulator: the `crossfade` build is installed, `Music/CrossfadeTest` is granted and scanned, the
crossfade control is set to **4s**, and the queue is loaded and paused partway through track 1. To
resume, boot that AVD, open AtomicAmp, press **Play**. The emulator's disk persists, so the grant,
library and setting all survive a shutdown.

The four excerpts are deliberately built as *real* transitions — the genuine last 35 s of one track
running into the genuine first 35 s of the next, because a crossfade judged against a spliced
excerpt tells you nothing. Two contrasting pairs:

| # | material | why |
|---|---|---|
| 1→2 | Willie Nelson, *Shotgun Willie* (24/192) | sustained country ending; the case a crossfade should flatter |
| 3→4 | NOFX, *Ribbed* (16/44.1) | abrupt punk ending; the case where 4s may sound plainly wrong |

If the verdict differs between those two, the answer is probably a shorter default rather than
no crossfade. Rebuild the material any time with `tools/make-crossfade-excerpts.ps1` — it cuts the
ends and starts with `ffmpeg -sseof`/`-t` and strips tags so the queue order is unambiguous.

## 8. What's next

1. ~~Answer the SAF/USB question~~ — **answered, see section 2.** The unit ships no document picker,
   and `LibraryScanner` now walks `file://` roots. What's left is to scan the real 3,350-file
   library off USB on the unit and see how long it takes and whether it holds up.
2. **Listen to the crossfade branch**, then merge or tune. Nothing needs building or wiring first —
   section 7 explains the session already waiting on the `ATOTO_S8_A10` emulator.
3. ~~Room migration tests~~ — **done**. `LibraryMigrationTest` covers 1→2, 2→3, 3→4 and the whole
   1→4 journey, asserting both schema and surviving rows. Two things to know:
   - `exportSchema` is now on and `app/schemas/*.json` is **committed on purpose** — those files
     *are* the old databases the tests build. Versions 1–3 were recovered by building the commit
     that shipped each one (`78b85bf`, `cd8fbf8`, `f8dfc19`), so they're the real schemas, not
     hand-written guesses. Never edit a schema file for a version that has shipped.
   - They run on **both API 29 and API 36**, and that pairing is deliberate rather than thorough
     for its own sake. The API 29 image carries **SQLite 3.22.0**, API 36 a much newer one, so the
     two runs straddle **SQLite 3.25** — the release where `ALTER TABLE ... RENAME` started
     rewriting foreign-key references in *other* tables. `MIGRATION_3_4` renames two tables, so
     that was the one plausible way it could behave differently in the car than on a desk. It
     doesn't; both sides agree.
4. **Gapless is still untested** — the one honest "unknown" left. Testing it by ear cannot detect a
   20 ms seam; Media3 ships `CapturingAudioSink` in `media3-test-utils-robolectric` for exactly
   this, which lets a test assert the captured sample count equals the sum of both files with no
   silence inserted. That needs a test-harness dependency added, so it is a piece of work rather
   than a check.
5. **Release mechanics are done** — see `RELEASING.md`. Adaptive icon, signing read from a
   gitignored `keystore.properties`, and a minified release build that was *run*, not just built
   (13.7 MB debug becomes 2.35 MB). What is still missing before publishing anywhere: a privacy
   policy, and a decision about whether Play is even the right destination given the app is built
   around one unit's missing document picker.
6. Sleep timer, tag editor, synced lyrics, widgets/skins — real Poweramp features, lower value here.
7. Android Auto and Chromecast are **deliberately deprioritized** — on a device that *is* the head
   unit they buy little.

## 9. Working style that fit this project

Verify claims on the device rather than asserting them; measure rather than estimate (scan
throughput, gain values, track boundaries); and when a measurement contradicts an earlier
conclusion, say so plainly — that happened more than once here and each time the correction
mattered.
