# Play Store listing copy

Paste-ready. **Do not add the word "Poweramp" anywhere in the title, description or keywords** —
it is someone else's trademark, and naming a competitor in a listing is one of the reliable ways
to get a review rejection.

---

## App name (30 char max)

```
AtomicAmp
```

## Short description (80 char max)

```
FLAC and hi-res player. Your folders or your own cloud storage. No account, no tracking.
```

*(80 exactly — count before editing.)*

## Full description (4000 char max)

```
AtomicAmp is a music player for people who keep their own files.

Point it at a folder and it plays what is in it. Point it at your own cloud storage and it streams
that too. No account with us, no subscription, and no library of ours in between — your
credentials stay on your device and the music comes straight from your storage.

WHAT IT PLAYS
• FLAC, including 24-bit and high sample rates
• MP3, M4A, AAC, OGG, Opus, WAV
• Cue sheets — an album ripped as one long file is split into real tracks, with the multi-file
  sheets that should not be split left alone
• Gapless playback, so a live album or a continuous mix plays without a seam

YOUR LIBRARY, LOCAL OR CLOUD
• Folders on the device, an SD card or a USB stick
• Or your own Backblaze B2 storage, streamed on demand
• Browse by songs, albums, artists, folders or playlists
• Scans folders you choose, indexed on device so it opens fast
• Album art from the file or from a cover image beside it
• Search, and an A–Z rail for getting down a long list quickly

SOUND
• 10-band graphic equaliser with preamp and presets
• Optional volume levelling, measured from the audio in real time rather than read from tags —
  useful when a library has none
• Equal-power fades, so pausing and resuming does not click

BUILT FOR A DASHBOARD
AtomicAmp started as a player for a car head unit, and it still shows in the good ways. The
interface is dark with no light mode, because a white screen at eye level at night is glare.
Touch targets and type are larger than a phone app would use. It can resume playing on its own
when the device powers up, which you can turn off. There is a full-screen artwork view for when
the screen is being glanced at rather than operated.

PRIVACY
No accounts. No analytics. No advertising. No trackers. The only network the app touches is your
own storage provider, with your own credentials. Your library index never leaves your device and
is deleted when you uninstall.

OPEN SOURCE
GPL-3.0. The whole thing is readable, and issues and patches are welcome:
https://github.com/punktilend/AtomicAmp
```

## Category and tags

- **Category:** Music & Audio
- **Tags:** music player, offline, FLAC, equalizer

## Data safety answers

Straightforward, because it is all true:

- **Does your app collect or share any user data?** → **No.** The app has no server and no
  analytics. Network access exists only so the app can reach a storage provider the user
  configures, with the user's own credentials, to stream the user's own files.
- **Is all user data encrypted in transit?** → Traffic to the storage provider is HTTPS.
- **Do you provide a way for users to request data deletion?** → N/A, nothing is collected.
  Uninstalling removes the local index and any stored credentials.

**This changed when cloud streaming was added.** The listing previously leaned on the app having no
`INTERNET` permission at all, which is no longer true and must not be claimed.

## Foreground service declaration

Play asks why the app uses a `mediaPlayback` foreground service:

```
AtomicAmp is a music player. The foreground service hosts audio playback so that music keeps
playing while the app is not on screen, and provides the media notification with play, pause and
skip controls. It runs only while there is playback, and stops when playback stops.
```

## Assets in this folder

| File | Where it goes |
|---|---|
| `play-icon-512.png` | App icon (512×512) |
| `play-feature-graphic-1024x500.png` | Feature graphic |
| `screenshots/1-now-playing.png` | Phone screenshot, 1080×2340 |
| `screenshots/2-fullscreen-art.png` | Phone screenshot, 1080×2340 |
| `screenshots/3-library.png` | Phone screenshot, 1080×2340 |

Screenshots are from a real Galaxy A37, not an emulator, so they show the app at the system font
scale a real person is using.

## Before you submit — the honest checklist

- [ ] Privacy policy hosted at a public URL (`PRIVACY.md` is written; GitHub Pages will serve it)
- [ ] Decide whether `resumeOnBoot` should still default to **on** for strangers — on a phone,
      music starting by itself after a reboot is surprising rather than helpful
- [ ] New personal Play accounts must run a closed test with 12 testers for 14 continuous days
      before applying for production. Confirm the current rule in the console — it is a two-week
      floor on the calendar no matter how ready the code is.
