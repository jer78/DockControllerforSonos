# Dock Controller for Sonos and Home Assistant

An Android app that turns a docked tablet or phone into an always-on controller
for your Sonos speakers, with Home Assistant controls alongside. It runs as a
normal app or as the device's screensaver, so it's there whenever the device
sits on its dock.

> **Not affiliated with, endorsed by or sponsored by Sonos or Home Assistant.**
> Sonos and Home Assistant are trademarks of their respective owners. This is
> an independent project that talks to your own speakers and server on your
> local network.

## Features

**Now Playing**
- Large album art, track, artist and album, with a blurred-art background
- Play/pause, next/previous, volume and mute
- Previous and Next Up tracks from the Sonos queue (tap Previous for details)
- Speaker Groups: join and unjoin rooms
- Sound Settings: bass, treble, loudness, Night Sound, Speech Enhancement and
  subwoofer, where the speaker supports them
- Optional spectrum analyzer behind the screen, driven by the microphone

**Cover Flow**
- A 3D carousel of albums with a glass-table reflection, shown when music is
  paused
- **History** mode: albums you've played that are in your Sonos Favorites
- **Favorites** mode: your Sonos Favorites (albums, playlists and radio)
- Tap an album to play it; Previous, Now Playing and Next Up cards along the
  bottom
- Ambient clock

**TV mode**
- Detects when a Sonos soundbar is playing TV audio and shows what's on, using
  a Home Assistant media player for the title, art and transport controls
- Drops into a TV screensaver after a configurable delay

**Home Assistant**
- Light: on/off, brightness, colour or colour temperature
- Climate: target temperature, mode, fan and preset

**Everywhere**
- Works as an Android screensaver (Daydream), with Settings available from it
- Dims automatically in a dark room using the light sensor

## Requirements

- Android 8.0 (API 26) or newer, on an arm64 or x86_64 device
- Sonos speakers on the same Wi-Fi network as the device
- Optional: a Home Assistant server and a long-lived access token, for the
  light, climate and TV mode features

Any music service connected to your Sonos system works; see
[Music services](#music-services) for the small differences.

## Setup

1. Install the app and open it. Settings opens automatically on first launch.
2. **General**: pick your Sonos speaker (the app scans your Wi-Fi for them).
3. **Cover Flow**: choose History or Favorites mode. In Favorites mode, tap
   **Refresh Favorites**.
4. **Home Assistant** (optional): enter your server URL and a long-lived access
   token (Home Assistant → your profile → Security → Long-lived access tokens),
   then pick your light, climate and media player entities.
5. Tap **Save & Apply**.

To use it as a screensaver: Android Settings → Display → Screen saver → choose
**Dock Controller for Sonos and Home Assistant**, and set it to start while
docked or charging.

## Music services

The app controls Sonos over your local network, so it works with whatever
Sonos is playing: Apple Music, Spotify, Amazon Music, TuneIn, Sonos Radio, your
own music library, AirPlay and Spotify Connect.

- Apple Music and Amazon Music artwork is fetched at a higher resolution; other
  services use the artwork Sonos provides.
- Previous and Next Up only appear when Sonos is playing from its queue, and
  not in shuffle.
- History mode only records albums that are saved as Sonos Favorites, since
  those are the only albums Sonos can reliably play again later.

## Building from source

1. Install [Android Studio](https://developer.android.com/studio).
2. Install **Python 3.12**. The build uses [Chaquopy](https://chaquo.com/chaquopy/)
   to bundle Python, and finds the build Python with `py -3.12` (the Windows
   Python launcher). On macOS or Linux, change `buildPython` in
   `app/build.gradle.kts` to your Python 3.12 path.
3. Open the project in Android Studio and run it on your device.

## How it works

- **Sonos**: [SoCo](https://github.com/SoCo/SoCo), a Python library for Sonos's
  local UPnP API, runs inside the app via Chaquopy (`app/src/main/python`).
- **Home Assistant**: its REST API, authenticated with your access token.
- **UI**: Kotlin and Jetpack Compose.

Your Home Assistant URL and token are stored only on the device.

## License

[MIT](LICENSE) © 2026 Jeremy Percival
