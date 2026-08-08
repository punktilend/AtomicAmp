# AtomicAmp

An open-source Android music player inspired by [Poweramp](https://powerampapp.com/)'s approach to
audio playback: a custom DSP pipeline that runs inline in the real playback path instead of
depending on the Android platform's built-in `AudioEffect`/`Equalizer`, which is low precision,
inconsistent across OEMs, and unavailable under float/offload output paths.

Poweramp itself is closed-source. Nothing here is copied from it — this is a clean-room
reimplementation of the *idea*, built on [Media3/ExoPlayer](https://developer.android.com/media/media3).

## Target device

The intended device is an **ATOTO S8 Android head unit**, operated in a moving vehicle. Specs read
off the hardware itself:

| | |
|---|---|
| OS | Android 10 (API 29) |
| Screen | 1280x720 px, density **1.0** → 1280x720 dp (1280x660 usable) |

That drives real layout constraints, and the UI adapts to measured size rather than assuming a
portrait phone:

- **Now Playing** goes two-pane when wide: album art + transport on the left, the equalizer as a
  row of vertical faders on the right. Stacking eleven horizontal sliders vertically — the obvious
  phone layout — does not fit in 660dp.
- **Library** lists use `GridCells.Adaptive`, giving 3 columns here, 2 at 1024dp, 1 in phone
  portrait, with no per-device column counts hardcoded.
- Touch targets are deliberately larger than phone intuition suggests: **density 1.0 is
  misleading**, because the panel is really ~210ppi. Every dp renders physically *smaller* on this
  unit than the same dp on a phone, so the platform's 48dp minimum lands under 6mm. Rows are 76dp
  and transport buttons 72dp.

Phones in portrait still get single-column layouts; the switch is on measured aspect ratio.

Note the bundled `ATOTO_S8` AVD is **1024x600 / API 36 and matches neither** the real screen nor
the real OS version. To test against the actual target, override a running emulator:

```bash
adb shell wm size 1280x720 && adb shell wm density 160
```

## Status: Phase 2 — music library + folder browser

Phase 1 (below) is done. Phase 2 adds a Poweramp-style independent music library: the app scans
folders the user explicitly grants (via SAF, not `MediaStore`) and organizes them by tag and by
real folder structure.

- **`:engine`** — the playback/DSP deliverable from Phase 1:
  - `PlaybackService`: a Media3 `MediaSessionService` hosting one `ExoPlayer`, with standard
    transport controls plus custom session commands for EQ control.
  - `dsp/BiquadFilter`: RBJ peaking-EQ biquad math.
  - `dsp/GraphicEqualizerAudioProcessor`: a runtime-adjustable 10-band graphic EQ + preamp,
    implemented as a Media3 `AudioProcessor` that runs on float PCM inside ExoPlayer's real audio
    sink.
  - `EngineRenderersFactory`: wires the equalizer directly into ExoPlayer's `DefaultAudioSink`.
  - `dsp/EqPreset`: built-in preset curves (Flat, Rock, Pop, Jazz, Classical, Bass Boost, Treble
    Boost, Vocal, Loudness), applied as a whole curve so coefficients recompute once, not per band.
  - `EqualizerSettingsStore`: persists bands/preamp/enabled/preset name. The service restores them
    in `onCreate` *before* the audio sink is built, so the first buffer already carries the user's
    curve. This matters on a head unit, where the process is killed every time the ignition goes
    off — an EQ that resets on every drive would be useless.
  - `PlaybackStateStore`: persists the queue, current index, and position, so playback resumes
    where it stopped. Checkpointed on transitions and pauses, plus every 5s while playing — an
    ignition-off kills the process outright, so there is no shutdown callback to rely on. Restores
    **paused**: powering on the unit should not start blasting audio by itself.
- **`:app`** — Compose UI, now with two destinations (`library`/`nowPlaying`):
  - `library/data`: a Room database (`Track`, `MusicFolder`) — the independent library.
  - `library/scan/LibraryScanner`: walks a granted SAF tree via `DocumentsContract`, reads tags
    and embedded album art with `MediaMetadataRetriever`, upserts in chunks so the UI fills in
    progressively as a scan runs. A rescan also prunes rows whose files have disappeared —
    otherwise reorganizing a USB stick leaves ghost entries that fail on play. Progress is
    reported as a running count rather than a percentage, since the total isn't known until the
    walk finishes.

    Measured throughput: **300 tracks in 6.6s — ~45 files/sec, 22ms per file** (emulator, local
    storage). Tag reading means opening every file, so that extrapolates to roughly 20s for 1,000
    tracks and ~2 minutes for 5,000. Expect real USB storage on the head unit to be slower.
  - `library/ui/LibraryScreen`: Songs / Albums / Artists / Folders tabs; the Folders tab mirrors
    the real filesystem structure the way Poweramp's does.
  - `PlayerScreen`: the Now Playing destination — album art, transport controls, and EQ wired live
    to the engine; adapts between the two-pane landscape and single-column portrait layouts.
  - `ui/VerticalSlider`: a rotated `Slider`, since Compose has no vertical one. Measures the child
    with width/height constraints swapped and rotates it back, so the child still behaves as a
    normal horizontal slider (touch handling and accessibility semantics stay intact).

The engine is the source of truth for equalizer state, not the UI. On connect, `PlayerViewModel`
pulls the real values via a `GET_EQ_STATE` session command rather than assuming zeros — otherwise a
UI launched against an already-running service would show a flat EQ while audio was audibly
equalized. Hand-adjusting a band marks the curve `Custom`; selecting a preset names it again.

Format support is still whatever Media3/MediaCodec handle without a native build: MP3, AAC/M4A,
FLAC, WAV, OGG Vorbis, Opus. ALAC/APE/DSD need the Media3 FFmpeg decoder extension and are deferred,
along with playlists, crossfade/gapless tuning, lyrics, widgets, and skins. Android Auto and
Chromecast are deprioritized — on a device that *is* the head unit they buy little.

Not yet validated on the physical ATOTO: whether it exposes a working SAF document picker, and
whether USB/SD storage is reachable through it. The library is built entirely on SAF folder grants,
so if the picker can't see removable storage the scanner will need a direct-filesystem fallback.

The target unit runs ATOTO's "AICE UI", which hides Developer options (the build-number tap is
inert) and exposes no network ADB — a full port scan of the device found nothing listening. So
nothing about it can be inspected over adb. `diagnostics/DiagnosticsScreen` exists for that: an
in-app report of build/OS version, real display metrics, storage volumes, and mount points, plus
buttons firing `Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS` and friends, since vendor skins
routinely hide the settings *entry* while leaving the activity reachable.

## Building

Requires a JDK (17+) and the Android SDK (compileSdk 35). On Windows with Android Studio installed,
its bundled JBR works fine as `JAVA_HOME`:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug
```

Install on a connected device/emulator:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
