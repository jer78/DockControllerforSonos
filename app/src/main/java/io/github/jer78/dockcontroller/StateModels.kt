package io.github.jer78.dockcontroller

// The three top-level screens MainActivity/SonosDreamService switch between,
// used to drive the drop/rise AnimatedContent transition instead of an
// unordered `when` (see DashboardUI.kt's SonosScreenTransitionSpec).
enum class DockScreen { NOW_PLAYING, COVER_FLOW, TV_AMBIENT }

// How Now Playing was left for Cover Flow, so the transition can follow the
// gesture: a swipe right or the Cover Flow button brings Cover Flow in
// FROM_LEFT, a swipe left FROM_RIGHT; everything else (swipe down, timers)
// is VERTICAL.
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

// The kinds of Home Assistant entity a Now Playing widget can control.
// domain is the entity_id prefix ("light.kitchen").
enum class HaWidgetType(val domain: String, val label: String) {
    CLIMATE("climate", "Climate"),
    LIGHT("light", "Light"),
    COVER("cover", "Cover"),
    LOCK("lock", "Lock");

    companion object {
        fun fromDomain(domain: String): HaWidgetType? = entries.find { it.domain == domain }
    }
}

// One configured widget position (Settings > Home Assistant). Positions are
// the index in SettingsManager.haWidgets; empty positions are null there.
data class HaWidgetSlot(val type: HaWidgetType, val entityId: String)

const val MAX_HA_WIDGETS = 5

// Latest state of each widget's entity, keyed by entity id, plus the
// widgets in display order. A widget only shows once its entity's state
// has been read, rather than showing placeholder values.
data class HAState(
    val widgets: List<HaWidgetSlot> = emptyList(),
    val entities: Map<String, HaEntityState> = emptyMap()
)

sealed interface HaEntityState {
    val entityId: String
    // HA's friendly_name, falling back to the entity id.
    val name: String
}

data class HaLightState(
    override val entityId: String,
    override val name: String,
    val isOn: Boolean = false,
    val brightness: Int = 0,
    // Color support, for the light dialog
    val colorModes: List<String> = emptyList(),
    val rgbColor: List<Int>? = null,
    val colorTempKelvin: Int? = null,
    val minColorTempKelvin: Int = 2000,
    val maxColorTempKelvin: Int = 6500
) : HaEntityState

// HA cover feature bits (CoverEntityFeature).
const val COVER_FEATURE_OPEN = 1
const val COVER_FEATURE_CLOSE = 2
const val COVER_FEATURE_SET_POSITION = 4
const val COVER_FEATURE_STOP = 8

data class HaCoverState(
    override val entityId: String,
    override val name: String,
    // "open", "closed", "opening", "closing" (or "unavailable"/"unknown").
    val state: String = "closed",
    // blind, curtain, shade, shutter, awning, garage, door, gate, window,
    // damper - or blank when the integration doesn't say.
    val deviceClass: String = "",
    // 0 (closed) - 100 (open), when the cover reports it.
    val position: Int? = null,
    val supportedFeatures: Int = 0
) : HaEntityState {
    val isMoving: Boolean get() = state == "opening" || state == "closing"
    val isOpen: Boolean get() = state == "open" || state == "opening" || (position ?: 0) > 0
    val supportsPosition: Boolean get() = supportedFeatures and COVER_FEATURE_SET_POSITION != 0
    val supportsStop: Boolean get() = supportedFeatures and COVER_FEATURE_STOP != 0
}

data class HaLockState(
    override val entityId: String,
    override val name: String,
    // "locked", "unlocked", "locking", "unlocking", "jammed", "open",
    // "opening" (or "unavailable"/"unknown").
    val state: String = "unknown",
    // HA's code_format is set when the lock needs a PIN to operate - not
    // supported here, so the dialog says so instead of failing silently.
    val requiresCode: Boolean = false
) : HaEntityState {
    val isLocked: Boolean get() = state == "locked"
    val isMoving: Boolean get() = state == "locking" || state == "unlocking" || state == "opening"
    val isJammed: Boolean get() = state == "jammed"
}

data class HaClimateState(
    override val entityId: String,
    override val name: String,
    val climateTemp: String = "0",
    val hvacState: String = "off",
    val isFanRunning: Boolean = false,
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
) : HaEntityState

// Something to do to one widget's entity, from Now Playing's widgets and
// their dialogs (see SonosDashboardManager.sendHaCommand).
sealed class HaCommand {
    abstract val entityId: String

    data class SetClimateTemperature(override val entityId: String, val temp: Double?, val high: Double?, val low: Double?) : HaCommand()
    data class SetHvacMode(override val entityId: String, val mode: String) : HaCommand()
    data class SetFanMode(override val entityId: String, val mode: String) : HaCommand()
    data class SetPresetMode(override val entityId: String, val mode: String) : HaCommand()
    data class SetLightOn(override val entityId: String, val on: Boolean) : HaCommand()
    data class SetLightBrightness(override val entityId: String, val brightness: Int) : HaCommand()
    data class SetLightRgb(override val entityId: String, val r: Int, val g: Int, val b: Int) : HaCommand()
    data class SetLightColorTemp(override val entityId: String, val kelvin: Int) : HaCommand()
    data class OpenCover(override val entityId: String) : HaCommand()
    data class CloseCover(override val entityId: String) : HaCommand()
    data class StopCover(override val entityId: String) : HaCommand()
    data class SetCoverPosition(override val entityId: String, val position: Int) : HaCommand()
    data class Lock(override val entityId: String) : HaCommand()
    data class Unlock(override val entityId: String) : HaCommand()
}
