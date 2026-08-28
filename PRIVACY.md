# Privacy Policy for AtomicAmp

**Last updated: 11 August 2026**

## The short version

AtomicAmp collects nothing about you and sends nothing to us. There is no analytics, no
advertising, no tracking and no account.

It does use the network, for exactly one thing: if you configure a cloud library, it talks to
**your own** storage provider with **your own** credentials, to list and stream **your own**
files. There is no AtomicAmp server involved and nothing is relayed through one.

## What the app does with your data

AtomicAmp is a music player. It reads audio files you explicitly point it at, and it keeps an index
of them so the library loads quickly. That index — track titles, artists, albums, file locations,
play position, equaliser settings — is stored **only on your device**, in the app's own private
storage, and is deleted when you uninstall the app.

Nothing is uploaded. There are no accounts, no analytics, no advertising, no crash reporting, no
tracking of any kind, and no third-party SDKs that collect data.

If you configure a cloud library, the credentials you enter are stored on your device only and are
sent to that provider alone in order to authenticate. Audio streams directly from the provider to
your device.

## Permissions, and why each exists

| Permission | Why |
|---|---|
| **Read audio / external storage** | To read the music files in folders you choose. Nothing outside the folders you grant is read. |
| **Foreground service / media playback** | To keep playing when the app is not on screen. This is what makes the notification controls work. |
| **Notifications** | To show the playback notification with track info and controls. |
| **Wake lock** | To stop the device sleeping mid-track and stalling playback. |
| **Receive boot completed** | Optional. If "Resume playing when the device starts" is enabled, this lets playback resume after the device powers on. It can be turned off in the app. |
| **Internet / network state** | Only to reach the cloud library you configure. With no cloud library configured, the app makes no network requests at all. |

## Album art

Artwork is read from your own files — either embedded in the track or from a cover image beside it —
and cached on your device so lists scroll smoothly. No artwork is fetched from the internet.

## Children

The app collects no data from anyone, including children.

## Changes

If this policy ever changes, the updated version will be published at this address and the date
above will change.

## Contact

AtomicAmp is open source under the GPL-3.0 licence. Questions and issues:
<https://github.com/punktilend/AtomicAmp>
