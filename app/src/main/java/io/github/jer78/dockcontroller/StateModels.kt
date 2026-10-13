package io.github.jer78.dockcontroller

// The three top-level screens MainActivity/SonosDreamService switch between,
// used to drive the drop/rise AnimatedContent transition instead of an
// unordered `when` (see DashboardUI.kt's SonosScreenTransitionSpec).
enum class DockScreen { NOW_PLAYING, COVER_FLOW, TV_AMBIENT }

// How Now Playing was left for Cover Flow, so the transition can follow the
// gesture: a swipe right brings Cover Flow in FROM_LEFT, a swipe left
// FROM_RIGHT; everything else (swipe down, the button, timers) is VERTICAL.
enum class ScreenSlide { VERTICAL, FROM_LEFT, FROM_RIGHT }

data class TrackState(
    val title: String = "Not Playing",
    val artist: String = "Connect Sonos",
    val album: String = "",
    val albumArtUrl: String = "",
    val isPlaying: Boolean = false,
    val isMuted: Boolean = false,
    val prevTrack: String = "None",
    val nextTrack: String = "None",
    val prevArtUrl: String = "",
    val nextArtUrl: String = "",
    val prevArtist: String = "",
    val prevAlbum: String = "",
    val nextArtist: String = "",
    val rawMetadata: String = "",
    val lastCommandSent: String = "",
    val groupMembers: List<GroupMemberVolume> = emptyList(),
    // Elapsed/total playback position, in seconds, as of the last poll (see
    // parseSonosTime in SonosDashboardManager.kt) - 0/0 for radio/live
    // streams and anything SoCo can't report a real position for.
    val positionSeconds: Int = 0,
    val durationSeconds: Int = 0,
    // Only the Sonos queue can be shuffled (not radio, TV, line-in, ...) -
    // the shuffle button hides when this is false.
    val shuffleAvailable: Boolean = false,
    val isShuffled: Boolean = false,
    // "OFF" / "ALL" / "ONE" - shown alongside shuffle, same availability.
    val repeatMode: String = "OFF",
    // More than one room playing together: isMuted and the main volume
    // slider are the whole group's, not just the selected speaker's.
    val isGrouped: Boolean = false
)

// A failed command (album play, favorite delete, ...), kept in
// SonosDashboardManager.recentErrors for the Metadata Debugger. timestampMs
// lets the error flash show only while it's fresh, instead of re-flashing a
// stale error every time a screen is (re)composed.
data class CommandError(
    val id: Long,
    val message: String,
    val timestampMs: Long
)

data class GroupMemberVolume(
    val name: String,
    val volume: Float,
    val isCoordinator: Boolean,
    val model: String = ""
)

data class AlbumHistoryItem(
    val artUrl: String,
    val artist: String,
    val albumTitle: String,
    val playUri: String = "",
    val playMetadata: String = ""
)

data class SonosSpeakerGroup(
    val name: String,
    val isJoined: Boolean,
    val ip: String,
    val model: String = ""
)

// Speaker-hardware audio EQ/features, as opposed to TrackState's per-track
// info - these barely ever change, so they're polled far less often. A
// null field means the coordinator doesn't support/report that feature
// (e.g. Night Sound/Speech Enhancement only exist on soundbars) - the UI
// hides the corresponding control rather than showing a dead toggle.
data class SonosAudioSettings(
    val isSoundbar: Boolean = false,
    val hasSubwoofer: Boolean = false,
    val nightMode: Boolean? = null,
    val dialogMode: Boolean? = null,
    val bass: Int? = null,
    val treble: Int? = null,
    val loudness: Boolean? = null,
    val subEnabled: Boolean? = null
)

data class HAState(
    val climateTemp: String = "0",
    val hvacState: String = "off",
    val lightOn: Boolean = false,
    val lightBrightness: Int = 0,
    val isFanRunning: Boolean = false,
    // Light color support, for the light dialog
    val lightColorModes: List<String> = emptyList(),
    val lightRgbColor: List<Int>? = null,
    val lightColorTempKelvin: Int? = null,
    val lightMinColorTempKelvin: Int = 2000,
    val lightMaxColorTempKelvin: Int = 6500,
    // Full climate detail, for the thermostat dialog
    val targetTemp: Double? = null,
    val targetTempHigh: Double? = null,
    val targetTempLow: Double? = null,
    val currentHumidity: Int? = null,
    val hvacAction: String = "idle",
    val hvacModes: List<String> = emptyList(),
    val fanMode: String = "",
    val fanModes: List<String> = emptyList(),
    val presetMode: String = "",
    val presetModes: List<String> = emptyList(),
    val minTemp: Double = 10.0,
    val maxTemp: Double = 35.0,
    val tempStep: Double = 0.5,
    val temperatureUnit: String = "°C"
)
