# Dock Controller for Sonos and Home Assistant

[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-ffdd00?style=for-the-badge&logo=buy-me-a-coffee&logoColor=black)](https://www.buymeacoffee.com/jeremypercival)

An Android app that turns a docked tablet or phone into an always-on controller
for your Sonos speakers, with Home Assistant controls alongside. It runs as a
normal app or as the device's screensaver, so it's there whenever the device
sits on its dock.

> **Not affiliated with, endorsed by or sponsored by Sonos or Home Assistant.**
> Sonos and Home Assistant are trademarks of their respective owners. This is
> an independent project that talks to your own speakers and server on your
> local network.

## Screenshots

![Now Playing screen with album art, playback and volume controls, and Home Assistant widgets along the top](docs/screenshots/nowplaying.jpg)

| Cover Flow | Grid |
|---|---|
| ![Cover Flow screensaver with the clock and weather above the album covers](docs/screenshots/coverflow.jpg) | ![Grid view with the A-Z bar above the album covers](docs/screenshots/grid.jpg) |

## Features

**Now Playing**
- Large album art, track, artist and album, with a blurred-art background
- Play/pause, next/previous, shuffle and repeat (off, all, one)
- Progress bar you can tap or drag to seek
- Volume and mute; when rooms are grouped, they control the whole group
- Previous (the song that actually played last, even across album changes)
  and Next Up (from the Sonos queue); tap Previous for details
- Speaker Groups: join and unjoin rooms; each grouped room also gets its own
  volume slider
- Sound Settings: bass, treble, loudness, Night Sound, Speech Enhancement and
  subwoofer, where the speaker supports them
- Optional clock above the artwork, in music mode, TV mode or both
- Optional spectrum analyzer behind the screen (or just over the artwork),
  driven by the microphone
- Swipe left, right or down to go to Cover Flow

**Cover Flow**
- A 3D carousel of albums standing on a glass table, shown when music is
  paused; the reflection, glass surface and side light are adjustable
- **History** mode: albums you've played that are in your Sonos Favorites
- **Favorites** mode: your Sonos Favorites (albums, playlists and radio)
- **Category filter**: a button in the top right cycles between all
  favorites, albums, playlists and radio stations, with a count of each
- **Grid view**: the button beside it swaps the 3D carousel for a grid of
  album covers, several rows deep, that scrolls left and right - much
  quicker for finding one album among hundreds of favorites. Tap a cover to
  play it, or long-press it to delete it from your Sonos Favorites (after a
  confirmation). Works in History mode too
- **A-Z bar** above the grid: tap a letter, or drag along the bar, to jump
  straight to it
- **Search**: the magnifier button opens an on-screen keyboard; type any
  part of a title or artist (case and accents don't matter) and the grid
  narrows as you type
- Cover Flow always opens on all favorites in the carousel
- Choose how many albums it holds: 50, 100, 250, 500 or unlimited (in
  Favorites mode, that many albums, that many playlists and that many radio
  stations)
- Tap an album to play it; tap its X to hide it from History, or (after a
  confirmation) delete it from your Sonos Favorites
- Previous, Now Playing and Next Up cards along the bottom, each of which
  can be hidden
- Ambient clock, in 12- or 24-hour time, with the current weather under the
  date when you have a Home Assistant weather widget
- Optional: when an album finishes, carry on with a random album from your
  Sonos Favorites

**TV mode**
- Detects when a Sonos soundbar is playing TV audio and shows what's on, using
  a Home Assistant media player for the title, art and transport controls
- The artwork letterboxes to 16:9, with a seek bar and time remaining
- Recognises TV episodes (from the show name Home Assistant reports, an
  "S2 E3"-style title, or a "Season 28, Ep. 41" line beside it) and shows the
  show's title logo in place of the artwork, from TMDB with a free API key or
  TVmaze without one
- Shows with no logo get their TVmaze poster instead, with the artwork
  reshaped to fit it, and the show's TVmaze background (when it has one)
  behind the screen
- Turn off **Replace TV Show Images** to keep the images Home Assistant sends
- Pick one fallback image for when there's no artwork, or rotate through them
- Drops into a TV screensaver after a configurable delay
- Experimental: unmutes automatically when a commercial break ends

**Home Assistant**
- Connect button in Settings that checks your URL and token and says what's
  wrong if it can't connect
- Up to 5 widgets across the top of Now Playing, each a light, climate,
  cover, lock, weather, script, scene, input boolean, sensor, binary sensor,
  fan or alarm panel, in whatever order you choose
- Widgets show the icon set for the entity in Home Assistant, if it has one
- Light: on/off, brightness, colour or colour temperature
- Climate: target temperature, mode, fan and preset; the widget shows a red
  flame for heat or a blue snowflake for cool, and glows while it's running
- Cover: open, close, stop and position, with an icon matching the kind of
  cover (blind, shade, curtain, garage door, gate, ...)
- Lock: lock or unlock, always behind a confirmation pop-up, with a number
  pad for locks that need a code. The pop-up waits for Home Assistant's
  answer and says "Incorrect code" if the code was wrong. Codes are never
  saved - each one is sent to Home Assistant once and then forgotten
- Weather: the current condition and temperature; tap for a near
  full-screen forecast with every detail the entity reports (feels like,
  humidity, wind, pressure, UV, ...), hourly with a temperature curve, and
  daily or day-and-night with temperature ranges, rain and wind
- Script, scene and input boolean: one tap runs, activates or toggles it,
  with no pop-up
- Sensor: the reading and its unit (temperature, power, air quality, ...)
- Binary sensor: doors, windows, motion, leaks and so on, lit amber when
  something's open or detected, red for hazards like smoke or water
- Fan: on/off, speed, oscillation, direction and presets; the icon spins
  while it's running
- Alarm panel: arm (home, away, night, ...) or disarm, with a number pad or
  keyboard for the code (wrong codes are reported, as for locks); pulses
  amber while arming and red when triggered

**Everywhere**
- Instant updates: changes on your speakers and in Home Assistant show up
  straight away, without waiting for the next refresh
- Works as an Android screensaver (Daydream), with Settings and every pop-up
  available from it
- Dims automatically in a dark room using the light sensor

## Requirements

- Android 8.0 (API 26) or newer, on an arm64 or x86_64 device
- Sonos speakers on the same Wi-Fi network as the device
- Optional: a Home Assistant server and a long-lived access token, for the
  widgets and TV mode features

Any music service connected to your Sonos system works; see
[Music services](#music-services) for the small differences.

## Setup

1. Install the app and open it. Settings opens automatically on first launch.
2. **General**: pick your Sonos speaker (the app scans your Wi-Fi for them).
3. **Cover Flow**: choose History or Favorites mode. In Favorites mode, tap
   **Refresh Favorites**. Then, on the Cover Flow screen itself, use the
   buttons in the top right to filter by albums, playlists or radio, and to
   switch to the grid view.
4. **Home Assistant** (optional): enter your server URL and a long-lived access
   token (Home Assistant → your profile → Security → Long-lived access tokens)
   and tap **Connect**. Then choose a type (light, climate, cover, lock,
   weather, script, scene, input boolean, sensor, binary sensor, fan or
   alarm panel) and
   an entity for each widget position you want, using the arrows to reorder
   them, and pick the media player for TV mode. Optionally add a free
   [TMDB](https://www.themoviedb.org/settings/api) API key for better TV show
   logos.
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
- Next Up only appears when Sonos is playing from its queue, and not in
  shuffle. Until a song change has been seen, Previous also comes from the
  queue.
- Shuffle and repeat only appear when Sonos is playing from its queue (not
  radio, TV, line-in or AirPlay).
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
- **Home Assistant**: its REST API, authenticated with your access token,
  plus its WebSocket API for instant updates.
- **UI**: Kotlin and Jetpack Compose.

Your Home Assistant URL and token, and your TMDB key, are stored only on the
device.

TV show logos come from [TMDB](https://www.themoviedb.org) (when you add an
API key) or [TVmaze](https://www.tvmaze.com). This product uses the TMDB API
but is not endorsed or certified by TMDB.

Home Assistant icons are drawn with [Material Design
Icons](https://pictogrammers.com/library/mdi/) by Pictogrammers, and weather
icons are Google's [Material Symbols](https://fonts.google.com/icons), both
under the Apache 2.0 license.

## License

[MIT](LICENSE) © 2026 Jeremy Percival
