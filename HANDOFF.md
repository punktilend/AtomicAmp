# AtomicAmp — handoff

Working notes for picking this project up cold. The README covers *what the code does*; this covers
**what was learned, what's unresolved, and what will waste your time if you don't know it.**

- Repo: <https://github.com/punktilend/AtomicAmp> (public, GPL-3.0)
- Local: `C:\Users\adamm\AtomicPowerAmp`
- Branches: `main` (all verified work), `crossfade` (built + verified, deliberately unmerged)
- **It runs on two devices now.** The ATOTO head unit it was built for, and a phone (verified on a
  Galaxy A37, Android 16). Section 10 covers what differs between them and why.
- **It plays two libraries now.** Local folders, and the SpAtomify B2 bucket streamed on demand.
  Section 11 covers that.

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

**Start here: three UI changes are committed but have never been run.** Each builds and the suite
is green, but none has been seen on a screen, and stacking a fourth on top of them would mean a
later change resting on something unproven. Clearing them is one short sequence — boot
`ATOTO_S8_A10`, add `Music/CrossfadeTest` through the built-in browser, then:

| Check | What to look for |
|---|---|
| **Edit tags** (Now Playing) | Fields populate from the file, a save sticks, and the library row updates without a rescan. The `FlacTags` writer underneath *is* well tested; the screen is not. |
| **Sleep timer** (equaliser pane) | Countdown ticks down, and playback pauses rather than stops when it fires. |
| **Library header on a phone** | All four buttons visible in two rows, no longer scrolled off the right edge. |

The blocker was environmental rather than code: the only emulator attached belonged to another
project (the AtomicShave AVD), and a second would not start on an alternate port.


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
4. ~~Gapless is untested~~ — **answered: it is gapless.** `GaplessPlaybackTest` plays two assets
   that are one continuous tone cut in half, 44,100 frames each, and counts what reaches the audio
   sink through the app's own processor chain. Exactly 88,200 frames arrive, so nothing is
   inserted at the join and nothing is dropped. Counting at the sink rather than the decoder is
   the point: it is the last stop before the hardware. By ear this was unanswerable, which is why
   it stayed unknown for so long.
5. **Release mechanics are done** — see `RELEASING.md`. Adaptive icon, signing read from a
   gitignored `keystore.properties`, and a minified release build that was *run*, not just built
   (13.7 MB debug becomes 2.35 MB). What is still missing before publishing anywhere: a privacy
   policy, and a decision about whether Play is even the right destination given the app is built
   around one unit's missing document picker.
6. **Feature gap against Poweramp, partly closed.** Built and tested since: a FLAC tag
   reader/writer (`FlacTags`, rewrites via a temp file so a failure cannot damage an original)
   with an edit screen; a sleep timer held as a wall-clock deadline and deliberately not
   persisted; and offline lyrics parsing (`LrcParser`/`LyricsLoader`, sidecar `.lrc` or embedded
   tag, nothing fetched). **Lyrics have no UI yet** — the parser is done and covered, the display
   is not written. Still missing entirely: home-screen widget, visualisations, skins.
7. Tag editing only works on files reachable by path. SAF grants are taken read-only, so on a
   phone most tracks are not editable — asking for write access is a different permission
   conversation than the one the user had when adding the folder. On the ATOTO everything is
   `file://`, so it all works there.
8. Android Auto and Chromecast are **deliberately deprioritized** — on a device that *is* the head
   unit they buy little.

## 9. Working style that fit this project

Verify claims on the device rather than asserting them; measure rather than estimate (scan
throughput, gain values, track boundaries); and when a measurement contradicts an earlier
conclusion, say so plainly — that happened more than once here and each time the correction
mattered.

## 10. Running on a phone as well as the dashboard

The app was built for one device that lies about itself, and putting it on an honest one exposed
four things at once. All are fixed; the reasoning matters more than the fixes.

**Sizes were compensation, not taste.** `dp` is 1/160 inch, the ATOTO reports 160 dpi while its
panel is ~210 ppi, so everything renders about 24% smaller there and every size was chosen to
cancel that out. On a phone that inverts and the app looks like accessibility scaling was left on.
`DeviceProfile.isHeadUnitLike()` picks between `CarUiScale`/`PhoneUiScale` and two type scales,
keyed off the same density fact that caused the problem. Android's car UI mode would be the
obvious signal and is unusable: AICE does not set it.

**Edge-to-edge is mandatory** for `targetSdk 35` on Android 15, and nothing touched window insets,
so the title sat behind the status bar. API 29 still fits the window for you, which is why the car
never showed it.

**System font scale is real.** The test phone is at 1.5x, which crushed fixed-width rows: header
labels truncated to "Libra", the sleep timer label to one character per line. Anything that was a
fixed `Row` of controls is a `FlowRow` now, and settings rows give the label the leftover width.

**Portrait never scrolled**, so the equaliser and sleep timer were unreachable below the fold, and
the fullscreen art view squeezed its text column to nothing. Portrait scrolls; the wide layout
deliberately does not, because in the car everything must be reachable without scrolling.

Defaults differ where they should: `resumeOnBoot` is on for a head unit and off for a phone, and
the label says "the car" or "the device" to match.

## 11. The cloud library

`SpAtomify` is a Backblaze B2 bucket -- ~25,000 objects, 471 GB, 16,398 FLACs. AtomicBlast already
streamed it; this brings that into AtomicAmp rather than maintaining two players.

It fits because **the library is keyed on a uri string and the scanner branches on scheme**, so
`b2://` is a third source through the same seam SAF and `file://` use.

Things worth knowing before changing any of it:

- **Stored uris are stable, signed urls are not.** A B2 download url carries an expiring token, so
  the database stores `b2://bucket/path` and a `ResolvingDataSource` signs it at open time. Putting
  a signed url in a row would give you a queue that stops working overnight.
- **The cache wraps the resolver, not the other way round.** That ordering is what makes the cache
  key the stable `b2://` uri. Keyed on the signed url, the same track would be cached afresh on
  every play and never hit once. Cache is in `filesDir`, not `cacheDir`, because the system may
  clear the latter whenever it likes.
- **The scan opens nothing.** One flat paginated listing covers the whole prefix; metadata is
  inferred from the path and rows are flagged inferred so the UI shows "guessed". Reading tags from
  sixteen thousand files over the network would be a scan measured in hours.
- **Art is a pointer until it is wanted.** The same listing finds the covers, so the scan records
  each one's `b2://` path for free and `CloudArt` downloads it on first play, once per album.
- **B2 calls check for a network first** and time out in eight seconds, not thirty. They sit in
  front of playback starting, and offline a cached track had been taking 24 seconds to play while
  connections timed out.

**Two things to fix before anyone else installs this:**

1. **The seeded B2 key is not bucket-scoped.** It can read every bucket on the account. It lives in
   settings rather than compiled into the apk, so replacing it is a one-line change, but a build
   for anyone else needs a key restricted to this bucket with only `listFiles` and `readFiles`.
2. **Cue sheets and durations are not handled for cloud tracks.** 969 cue sheets in the bucket are
   ignored, and duration reads 0:00 in lists until a track has been played once.
