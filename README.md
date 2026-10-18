# Dock Controller for Sonos and Home Assistant

[![Download the app](https://img.shields.io/badge/Download-Android%20app-3ddc84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/jer78/DockControllerforSonos/releases/latest)
[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-ffdd00?style=for-the-badge&logo=buy-me-a-coffee&logoColor=black)](https://www.buymeacoffee.com/jeremypercival)

**New here?** See [How to install](#how-to-install): it takes about five minutes,
no computer needed.

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
  (how blurred is adjustable, separately for TV mode if you like)
- Play/pause, next/previous, shuffle and repeat (off, all, one)
- Progress bar you can tap or drag to seek
- Volume and mute; when rooms are grouped, they control the whole group.
  While muted, the volume bar says "Muted" and flashes in a color from the
  album art
- Previous (the song that actually played last, even across album changes)
  and Next Up (from the Sonos queue); tap Previous for details
- **Browse the queue**: open the album's or playlist's track list, see every
  song in the queue with its length and which one is playing, and tap one to
  jump straight to it
- Speaker Groups: join and unjoin rooms; each grouped room also gets its own
  volume slider
- Sound Settings: bass, treble, loudness, Night Sound, Speech Enhancement and
  subwoofer, where the speaker supports them
- Optional clock, in music mode, TV mode or both. In TV mode it sits above
  the show's title and takes turns with the time the show will end
- Optional spectrum analyzer behind the screen (or just over the artwork),
  driven by the microphone
- Swipe left, right or down to go to Cover Flow

**Cover Flow**
- A 3D carousel of albums standing on a glass table, shown when music is
  paused; the reflection, glass surface and side light are adjustable
- Shows all your Sonos Favorites: albums, playlists and radio
- **Keeps favorites past Sonos's limit**: the app saves every favorite it
  finds, and keeps it after it's removed from Sonos. Remove favorites from
  Sonos to make room for new ones there, and they stay in Cover Flow
- **Controls** fold away behind one button in the top right; tap it to show
  the filter, sort, refresh, search and view buttons
- **Category filter**: cycles between all favorites, albums, playlists and
  radio stations, with a count of each
- **Genre filter**: cycles through the genres of your Apple Music albums
  (looked up from Apple's catalog)
- **Sort** albums by artist or by album title, remembered for next time.
  Sorted by artist, each album shows its artist first, and playlists are
  grouped under V (Various Artists)
- **Refresh** your Sonos Favorites right from Cover Flow, and see how many
  are new
- **Grid view**: the button beside it swaps the 3D carousel for a grid of
  album covers, several rows deep, that scrolls left and right - much
  quicker for finding one album among all your favorites. Tap a cover to
  play it, or long-press it to delete it (after a confirmation). Left alone,
  it drifts slowly sideways, and picks up where it was after a trip to Now
  Playing
- **Recently played** in the grid: as it drifts, each of your last 5
  favorites played flips out to the front for a few seconds, saying when it
  was last played, then flips back into its place (Settings → Cover Flow /
  Grid → Show Recently Played)
- **A-Z bar** above the grid: tap a letter, or drag along the bar, to jump
  straight to it. In Cover Flow it slides up along the bottom while you swipe
  through the albums, with the track cards stepping aside for it
- **Home Assistant widgets** slide in from the left when someone's near,
  pushing the clock aside (and stepping in for its weather): when you touch
  the screen, until the albums start moving by themselves again, or while a
  Home Assistant motion sensor you pick detects someone. If there isn't room
  for both, the clock makes way until they go. You can also show them always,
  or never (Settings → Cover Flow / Grid, once Home Assistant is connected)
- **Search**: the magnifier button opens an on-screen keyboard; type any
  part of a title or artist (case and accents don't matter) and the grid
  narrows as you type
- Cover Flow always opens on all favorites in the carousel
- Tap an album to play it, or tap its X to delete it (after a
  confirmation). A favorite still on Sonos is deleted from Sonos too
- If a favorite that's no longer on Sonos won't play (say the music service
  removed the album), the app asks whether to delete it or keep it
- Previous, Now Playing and Next Up cards along the bottom, each of which
  can be hidden
- Ambient clock, in 12- or 24-hour time, with the current weather under the
  date when you have a Home Assistant weather widget
- Optional: when an album finishes, carry on with a random album from your
  Sonos Favorites

**Managing favorites** (Settings > Favorites)
- Lists every favorite together, each with a badge: **Sonos** (still in your
  Sonos Favorites) or **App only** (kept by the app after it was removed
  from Sonos), with a filter for each
- **Remove from Sonos** frees up a Sonos slot but keeps the favorite in the
  app
- **Delete** removes a favorite for good: from Sonos and the app, or, for
  an app-only favorite, from the app alone (Sonos isn't touched)

**TV mode**
- Detects when a Sonos soundbar is playing TV audio and shows what's on, using
  a Home Assistant media player for the title, art and transport controls
- The artwork letterboxes to 16:9, with a seek bar and time remaining
- The clock takes turns with the time the show ends, marked by a gold
  **ENDS AT** badge; turn off **Show Ends At Time** in Settings > Now Playing
  to keep just the clock
- Recognises TV episodes (from the show name Home Assistant reports, an
  "S2 E3"-style title, or a "Season 28, Ep. 41" line beside it) and shows the
  show's title logo in place of the artwork, from TMDB with a free API key
- Without a TMDB key, or for shows TMDB has no logo for, the show's TVmaze
  poster is shown instead, with the artwork reshaped to fit it, and the
  show's TVmaze background (when it has one) behind the screen
- Turn off **Replace TV Show Images** to keep the images Home Assistant sends
- Logos, posters and YouTube thumbnails are saved on the device once found,
  so each show is only looked up once. **Saved TV Artwork** (Settings > Home
  Assistant) lists them; delete one to have it looked up again (say, if it
  matched the wrong show), or delete them all. Artwork not shown for 14 days
  is deleted by itself to save space
- Pick one fallback image for when there's no artwork, or rotate through them
- Drops into a TV screensaver after a configurable delay
- Experimental: Auto-Unmute After Commercials and YouTube Thumbnails (see
  below)

**Home Assistant**
- Connect button in Settings that checks your URL and token and says what's
  wrong if it can't connect
- Up to 10 widgets across the top of Now Playing, each a light, climate,
  cover, lock, weather, script, scene, input boolean, sensor, binary sensor,
  fan or alarm panel, in whatever order you choose
- Rearrange them right on Now Playing: hold a widget until they all wiggle,
  then drag it to its new place
- Optional label under each widget: Home Assistant's name, or your own
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
- Works as an Android screensaver (Daydream), with every pop-up available
  from it; Settings opens in the app, where it scrolls properly
- Dims automatically in a dark room using the light sensor, and fades Cover
  Flow and the TV screensaver to black once the lights go out (a tap shows it
  again for 30 seconds; it comes back by itself when the lights go on)

## Experimental features

These work, but may not suit every TV or service. Turn them on in Settings.

### Auto-Unmute After Commercials

Mute the TV during an ad break and the dock unmutes it for you when the
show comes back on. Turn it on in **Settings → Home Assistant →
Experimental**.

- **How it works**: while a show plays, its time remaining only counts down.
  When an ad break ends, the TV reports the show's time remaining again,
  which jumps up by more than 10 seconds compared with the ad's. That jump
  is the cue to unmute the Sonos speaker.
- **What it needs**: TV mode set up with a Home Assistant media player that
  reports the playing time (position and duration).
- **Limits**: it only unmutes, never mutes, so mute the ads yourself. If
  your TV or streaming app doesn't report the time, or reports it
  differently, it won't trigger; it can also unmute when you skip back
  in a show while muted.

### YouTube Thumbnails

Most TVs send no artwork for YouTube, so TV mode shows the video's own
thumbnail instead. Turn it on in **Settings → Home Assistant →
Experimental**.

- **How it works**: when the media player's app is YouTube, the thumbnail
  comes straight from the video ID if the player reports one (Google Cast
  usually does). Players that only report the title and channel, like Apple
  TV, need a free [YouTube Data API](https://console.cloud.google.com/apis/library/youtube.googleapis.com)
  key to search for the video, preferring a result from that channel.
- **What it needs**: TV mode set up with a Home Assistant media player that
  reports the app (`app_name` or `app_id`).
- **Limits**: without a key (or the video ID), nothing changes and the TV
  mode fallback artwork shows. Each new video uses one search; the free
  quota allows about 100 a day. Settings shows today's searches (counted on
  the device, as Google doesn't report what's left), warns once the quota
  runs out, and says how the last search went. A search can occasionally
  pick the wrong video when titles are similar.
- **Your key, your responsibility**: the API key is your own, created in
  your own Google Cloud project. You're responsible for how it's used,
  for staying within Google's quotas and the [YouTube API Services Terms of
  Service](https://developers.google.com/youtube/terms/api-services-terms-of-service),
  and for any charges on your Google Cloud account. The app only uses the
  key for the searches described above, and it's never sent anywhere but
  Google. Restricting the key to the YouTube Data API v3 in Google Cloud
  is recommended.

## What you need

- An Android tablet or phone running Android 8 or newer. **It has only been
  tested on a Google Pixel Tablet**; other devices should work, but layouts may
  not fit smaller screens well.
- Sonos speakers, on the same Wi-Fi as the tablet.
- Optional: a Home Assistant server, for the smart-home buttons and TV mode.

Any music service you use with Sonos works; see
[Music services](#music-services) for the small differences.

## How to install

The app isn't in the Google Play Store, so you download it from this page and
install it yourself. Android shows a few warnings along the way. They appear
for every app from outside the Play Store, not because something is wrong.
Do all of this **on the tablet itself**.

1. **Download the app.** On the tablet, open the
   [latest release page](https://github.com/jer78/DockControllerforSonos/releases/latest).
   Scroll down to **Assets** and tap the file ending in **.apk** (for example
   `DockController-v1.0.36.apk`). It's about 55 MB.
2. **If Chrome warns you about the file** ("This type of file can harm your
   device"), tap **Download anyway**.
3. **Open the file.** When the download finishes, tap **Open** in the message
   at the bottom of the screen. If you missed it, open the **Files** app and
   look in **Downloads**.
4. **Allow the install.** Android will say it can't install unknown apps from
   this source. Tap **Settings**, switch on **Allow from this source**, then tap
   the back arrow.
5. **Tap Install.** If a Google Play Protect message appears:
   - "App scan recommended": tap **Install without scanning** (or **Scan app**,
     then **Install** when it's done).
   - "Unsafe app blocked" or "Unrecognised app": tap **More details**, then
     **Install anyway**.
6. **Tap Open** when it's finished, and carry on with [Setup](#setup) below.

You can switch **Allow from this source** off again afterwards; it isn't
needed to use the app, only to install it.

**Updating:** when a new version comes out, download and install it the same
way. Your settings are kept.

<details>
<summary><b>For advanced users</b>: installing from a computer, and "App not installed" errors</summary>

To install from a computer with [adb](https://developer.android.com/tools/adb),
turn on **USB debugging** on the device (Settings → About tablet → tap **Build
number** seven times, then Settings → System → Developer options), connect it
by USB, and run:

```
adb install -r DockController-v1.0.36.apk
```

Every release is signed with the same key. If Android says **App not
installed** or the package **conflicts with an existing package**, the copy
already on the device was signed differently (for example, one you built
yourself). Uninstall it first, then install the release; this resets the app's
settings.

</details>

## Setup

1. Open the app. Settings opens automatically on first launch.
2. **Setup**: pick the Sonos speaker for the room the dock is in (the app
   scans your Wi-Fi for them). Then choose which kinds of favorites to show
   (albums, playlists, radio) and tap **Refresh Favorites**. On the Cover
   Flow screen itself, use the buttons in the top right to filter by albums,
   playlists or radio, and to switch to the grid view. The **Favorites** tab
   lists every favorite the app keeps, on Sonos or not.
3. **Home Assistant** (optional): enter your server URL and a long-lived access
   token (Home Assistant → your profile → Security → Long-lived access tokens)
   and tap **Connect**. Once connected, the token is hidden; tap **Change**
   to replace it. Then choose a type (light, climate, cover, lock,
   weather, script, scene, input boolean, sensor, binary sensor, fan or
   alarm panel) and
   an entity for each widget position you want, using the arrows to reorder
   them, and pick the media player for TV mode. Optionally add a free
   [TMDB](https://www.themoviedb.org/settings/api) API key for better TV show
   logos.
4. Tap **Save & Apply**.

**To have it come on whenever the tablet is docked**, make it the screen saver:

- **Pixel Tablet**: Settings → **Hub mode** → **Screen saver** → choose
  **Dock Controller for Sonos and Home Assistant**.
- **Other devices**: Settings → **Display** → **Screen saver** → choose
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

## Known limitations

- **About 70 Sonos Favorites at a time (a Sonos limit).** Sonos itself caps
  how many favorites a system can hold, at around 70 across albums,
  playlists and radio. Cover Flow can hold more: once the app has synced a
  favorite, you can remove it from Sonos to free up a slot, and it stays in
  the app until you delete it here. To make room:
  1. Tap **Refresh Favorites** (in Settings > Setup, or in Cover Flow) so
     the app has saved everything currently on Sonos. A favorite removed
     from Sonos before the app has seen it is gone for good.
  2. Remove older ones with **Remove from Sonos** in Settings > Favorites,
     or in the Sonos app. They're then marked **App only** and still play
     from Cover Flow.
  3. To get rid of one completely, use **Delete** in Settings > Favorites
     or the X in Cover Flow.
- Albums only appear in Cover Flow once they've been saved as Sonos
  Favorites at least once, since that's where the app gets what it needs to
  play them.
- A favorite that's no longer on Sonos is played from the link the app saved
  when it was. If the music service changes or removes the album, that link
  can stop working; the app then offers to delete it.

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

Your Home Assistant URL and token, and your TMDB and YouTube keys, are stored
only on the device.

TV show logos come from [TMDB](https://www.themoviedb.org) (when you add an
API key) or [TVmaze](https://www.tvmaze.com). This product uses the TMDB API
but is not endorsed or certified by TMDB.

YouTube thumbnails use YouTube API Services, with your own API key, and
YouTube's [Terms of Service](https://www.youtube.com/t/terms) apply. This
app is not affiliated with or endorsed by YouTube or Google.

Home Assistant icons are drawn with [Material Design
Icons](https://pictogrammers.com/library/mdi/) by Pictogrammers, and weather
icons are Google's [Material Symbols](https://fonts.google.com/icons), both
under the Apache 2.0 license.

## License

[MIT](LICENSE) © 2026 Jeremy Percival
