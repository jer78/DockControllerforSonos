package com.example.sonosdockcontroller

// The three top-level screens MainActivity/SonosDreamService switch between,
// used to drive the drop/rise AnimatedContent transition instead of an
// unordered `when` (see DashboardUI.kt's SonosScreenTransitionSpec).
enum class DockScreen { NOW_PLAYING, COVER_FLOW, TV_AMBIENT }

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
    val rawMetadata: String = "",
    val lastCommandSent: String = "",
    val groupMembers: List<GroupMemberVolume> = emptyList()
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
