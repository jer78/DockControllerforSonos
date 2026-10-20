# Plan: Music Assistant support

Status: **not started** - written 2026-10-19 for later.

Goal: let Dock Controller play through [Music Assistant](https://music-assistant.io)
(MA) as an alternative to talking to Sonos directly through SoCo, in this
same app, keeping the screens (Now Playing, Cover Flow, grid, Home Assistant
widgets, screensaver, artist suggestions) shared between both.

> **Before starting:** MA's API has been changing release to release. Check
> its current docs for the WebSocket API (commands, events, authentication)
> and for the Home Assistant actions the MA integration adds, and adjust the
> phases below to match.

## Why this app, not a new one

- About 80% of the value is the shared UI and features; two apps would mean
  fixing everything twice (already a pain with MainActivity and
  SonosDreamService duplicating each other).
- MA users already run Home Assistant, the app's natural audience.
- If it ever goes on the Play Store it needs renaming anyway (Sonos
  trademark): e.g. "Dock Controller", "works with Sonos, Music Assistant and
  Home Assistant".

## What maps across

| Feature | Music Assistant |
|---|---|
| Now Playing, transport, seek, volume, mute | Direct equivalents |
| Queue browsing, Previous, Next Up | Yes - MA queues |
| Shuffle, repeat, crossfade | Yes - per queue |
| Grouping | MA's own player groups / sync, not Sonos room groups |
| Cover Flow and grid | Better: MA library + favorites, no ~70 limit, no saved-favorites workaround |
| Genre filter, Prefer Same Genre | If MA has genres for the albums (check) |
| Artist suggestions | Better: search MA providers, so suggestions get a real **Play** button |
| Live albums, Continue With a Random Album, Replay | Same logic, new backend |
| Recently played, Now Playing in the grid | Same logic, keyed on MA item IDs |
| Sound settings (bass, treble, Night Sound, sub) | No - Sonos hardware only; hide |
| TV mode (soundbar TV input) | No - Sonos only; the HA media-player parts still work |
| Skipped explicit song notice | Not needed (Sonos-specific) |

## Phases

### 0. Tidy first (recommended)

Fold the duplicated host code in `MainActivity.kt` and `SonosDreamService.kt`
(screen switching, swap-between-songs, suggestions, notices) into one shared
composable. Halves the wiring work in every later phase.

### 1. Prototype through Home Assistant (a few days)

- Setup option: "Music source: Sonos / Music Assistant player (via Home
  Assistant)", with an entity picker for the MA `media_player`.
- Reuse TV mode's HA media-player path (`HomeAssistantManager.fetchHAMediaPlayer`,
  `sendMediaPlayerCommand`, `seekMediaPlayer`, the HA WebSocket push) to show
  and control it as music rather than TV.
- Cover Flow keeps showing Sonos favorites for now.
- Purpose: try it on a real setup before the big work.

### 2. Player backend interface (no visible change)

Put an interface between the app and the speaker code:

```kotlin
interface PlayerBackend {
    val capabilities: BackendCapabilities   // eq, tvMode, crossfade, groups, search, ...
    val nowPlaying: StateFlow<TrackState>
    suspend fun favorites(): List<AlbumHistoryItem>
    suspend fun queue(): SonosQueue?
    fun command(action: String)             // play, pause, next, previous
    fun setVolume(volume: Float); fun setMute(muted: Boolean)
    fun setShuffle(on: Boolean); fun setRepeat(mode: String); fun setCrossfade(on: Boolean)
    fun play(item: AlbumHistoryItem); fun playQueueTrack(index: Int); fun seek(seconds: Int)
}
```

- `SonosBackend` = today's code (`sonos_bridge.py` + the Sonos parts of
  `SonosDashboardManager.kt`) moved behind it unchanged.
- `SonosDashboardManager` keeps the app logic (live albums, random album,
  suggestions, recently played, swap-between-songs) and talks only to the
  interface.
- Screens hide what `capabilities` says isn't there (sound settings, TV mode).
- Test thoroughly on Sonos: nothing should change.

### 3. Music Assistant backend over its WebSocket API (a few weeks of evenings)

In Kotlin with OkHttp's WebSocket, like `HomeAssistantEvents.kt` (no new
Python needed). In order:

1. Connect/authenticate; pick a player; subscribe to player and queue events.
2. Now Playing from the player's current queue item; transport, volume, seek.
3. Queue: browse, play a track, shuffle/repeat/crossfade.
4. Library: favorite albums (and playlists/radio) for Cover Flow and the
   grid, with artwork, artist and genre. Category and genre filters, search.
5. Grouping via MA groups, if wanted.

Settings: MA server URL (+ token if MA requires one), player picker; Setup's
crossfade switch moves to the queue setting.

### 4. Make the most of it

- Artist suggestions: search MA for the suggested album; **Play** it directly
  instead of "add it in the Sonos app".
- Continue With a Random Album / Prefer Same Genre over the whole MA library.

## Risks and open questions

- MA API stability: pin to a minimum MA version and say so in the README.
- Authentication: check whether the current MA server requires a token.
- Artwork URLs: MA may serve them through its own image proxy (needs the
  server URL, maybe auth) - check Coil can load them.
- Grouping semantics differ from Sonos; the Speaker Groups dialog may need
  a different look.
- Keeping the Sonos path well tested once there are two backends.

## Done when

- Picking Music Assistant in Setup gives Now Playing, queue, Cover Flow/grid
  and suggestions that play, on a real MA install.
- Sonos behaves exactly as before.
- README covers setup for both, and what each one can't do.
