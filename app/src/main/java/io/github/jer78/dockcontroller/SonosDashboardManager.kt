package io.github.jer78.dockcontroller

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock

class SonosDashboardManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val settingsManager = SettingsManager(appContext)
    private val haManager = HomeAssistantManager()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _trackState = MutableStateFlow(TrackState())
    val trackState: StateFlow<TrackState> = _trackState.asStateFlow()

    // Most recent first, capped at MAX_RECENT_ERRORS.
    private val _recentErrors = MutableStateFlow<List<CommandError>>(emptyList())
    val recentErrors: StateFlow<List<CommandError>> = _recentErrors.asStateFlow()
    private var nextErrorId = 0L

    private val _haState = MutableStateFlow(HAState())
    val haState: StateFlow<HAState> = _haState.asStateFlow()

    private val _isTvMode = MutableStateFlow(false)
    val isTvMode: StateFlow<Boolean> = _isTvMode.asStateFlow()

    private val _currentVolume = MutableStateFlow(30f)
    val currentVolume: StateFlow<Float> = _currentVolume.asStateFlow()

    private val _audioSettings = MutableStateFlow(SonosAudioSettings())
    val audioSettings: StateFlow<SonosAudioSettings> = _audioSettings.asStateFlow()

    private val _albumHistory = MutableStateFlow<List<AlbumHistoryItem>>(emptyList())
    val albumHistory: StateFlow<List<AlbumHistoryItem>> = _albumHistory.asStateFlow()

    // The album last centered in Cover Flow, so leaving for Now Playing (or
    // switching between the app and the screensaver) resumes the rotation
    // there instead of restarting from the first album. Only read when Cover
    // Flow is first composed, so it doesn't need to be observable.
    var coverFlowCenterAlbum: AlbumHistoryItem? = null

    // Every favorited ALBUM, whatever Cover Flow's category filters - used
    // for matching what's playing (to learn album artists) and for
    // Continue With a Random Album, not shown directly in the UI.
    private val _favoriteAlbumsRef = MutableStateFlow<List<AlbumHistoryItem>>(emptyList())

    // Every favorite of every kind, on Sonos or kept only here (see
    // SavedFavorites) - the source both lists above are built from, and
    // what Settings > Favorites manages. Changes go through savedLock, so a
    // refresh and a delete can't overwrite each other.
    private val savedFavoritesStore = SavedFavoritesStore(appContext)
    private val savedLock = kotlinx.coroutines.sync.Mutex()
    private var savedLoaded = false
    private val _savedFavorites = MutableStateFlow<List<AlbumHistoryItem>>(emptyList())
    val savedFavorites: StateFlow<List<AlbumHistoryItem>> = _savedFavorites.asStateFlow()
    // Cover Flow's category filters (Settings > Cover Flow > Sync
    // Categories) as last applied: albums, playlists, radio.
    @Volatile private var shownKinds = Triple(true, true, true)

    // A favorite the user tried to play that's no longer on Sonos and whose
    // saved data didn't play - the hosts ask whether to delete or keep it.
    private val _unplayableFavorite = MutableStateFlow<AlbumHistoryItem?>(null)
    val unplayableFavorite: StateFlow<AlbumHistoryItem?> = _unplayableFavorite.asStateFlow()

    // Artist names learned by observing a favorited album actually play
    // (Sonos never reports a real artist for album favorites, and only Apple
    // Music ones get looked up during the sync), keyed by "id:<albumKey>" -
    // or, for entries learned by older versions, the normalized album title.
    // Backfilled into Cover Flow's favorites.
    private val _favoriteArtistCache = MutableStateFlow<Map<String, String>>(emptyMap())

    // The favorite this app last started, and when - tells apart favorites
    // that share a title when it starts playing (see pickPlayingFavorite).
    @Volatile private var lastStartedFavorite: Pair<AlbumHistoryItem, Long>? = null

    private val _discoveredSpeakers = MutableStateFlow<List<String>>(emptyList())
    val discoveredSpeakers: StateFlow<List<String>> = _discoveredSpeakers.asStateFlow()

    private val _speakerGroups = MutableStateFlow<List<SonosSpeakerGroup>>(emptyList())
    val speakerGroups: StateFlow<List<SonosSpeakerGroup>> = _speakerGroups.asStateFlow()

    private val _favoritesRefreshStatus = MutableStateFlow("Idle")
    val favoritesRefreshStatus: StateFlow<String> = _favoritesRefreshStatus.asStateFlow()
    // Item count from the last completed favorites refresh - null while one is
    // running or after it failed.
    private val _favoritesRefreshCount = MutableStateFlow<Int?>(null)
    val favoritesRefreshCount: StateFlow<Int?> = _favoritesRefreshCount.asStateFlow()

    private var pollingJob: Job? = null

    // "Previous" is the song that actually played before the current one,
    // remembered here as songs change. The queue alone can't provide that:
    // picking a new album replaces the whole queue, losing the song that
    // was just playing. lastSeenTrack is the song currently playing;
    // lastPlayedTrack is the one before it.
    private var lastSeenTrack: TrackDetails? = null
    private var lastPlayedTrack: TrackDetails? = null

    // Set by whichever host (MainActivity / SonosDreamService) is currently
    // showing an ambient screen (Cover Flow or TV Ambient) - those run most
    // of the time on a screensaver, so polling backs off while idle there
    // and speeds back up on Now Playing, where snappy updates actually matter.
    private val _isIdleScreen = MutableStateFlow(false)
    fun setIdleScreen(idle: Boolean) {
        _isIdleScreen.value = idle
    }

    companion object {
        private const val ACTIVE_POLL_INTERVAL_MS = 2000L
        private const val IDLE_POLL_INTERVAL_MS = 6000L
        private const val ACTIVE_HA_POLL_INTERVAL_MS = 5000L
        private const val IDLE_HA_POLL_INTERVAL_MS = 15000L
        // Fallback refresh while speaker events are flowing - events cover
        // every real change, this just catches anything missed. Playback
        // position ticks along locally in the UI in between.
        private const val ACTIVE_EVENT_FALLBACK_MS = 10000L
        private const val FORECAST_REFRESH_MS = 15 * 60 * 1000L
        private const val FORECAST_RETRY_MS = 60 * 1000L
        private const val IDLE_EVENT_FALLBACK_MS = 30000L
        private const val FAVORITES_REFRESH_INTERVAL_MS = 60 * 60 * 1000L
        private const val GROUPS_REFRESH_INTERVAL_MS = 10000L
        // The bridge pages through every favorite (100 per round trip to
        // the speaker), so large collections need longer than a single call.
        private const val FAVORITES_FETCH_TIMEOUT_MS = 20000L
        // How long after this app starts a favorite that it's assumed to be
        // what's playing, when telling apart same-titled favorites.
        private const val LAST_STARTED_FAVORITE_TRUST_MS = 10 * 60 * 1000L
        private const val MAX_RECENT_ERRORS = 5
        // How far time remaining must jump UP between TV polls to count as
        // coming back from a commercial - well clear of poll/clock jitter,
        // well under the minutes a show and an ad typically differ by.
        private const val AD_END_REMAINING_JUMP_SECONDS = 10
        // How early before the final queue track's predicted end a STOPPED
        // still counts as the album ending - slack for Sonos reporting whole
        // seconds and refresh timing.
        private const val ALBUM_END_WINDOW_SECONDS = 10
        @Volatile
        private var instance: SonosDashboardManager? = null

        fun getInstance(context: Context): SonosDashboardManager {
            return instance ?: synchronized(this) {
                instance ?: SonosDashboardManager(context).also { instance = it }
            }
        }
    }

    // Reloads Cover Flow's favorites with the saved category filters.
    private suspend fun reloadFavorites() {
        refreshFavorites(
            settingsManager.filterAlbums.first(),
            settingsManager.filterPlaylists.first(),
            settingsManager.filterRadio.first()
        )
    }

    // Called right after Settings saves, so a new speaker or new category
    // filters show in Cover Flow straight away.
    fun refreshAfterSettingsSave() {
        scope.launch { reloadFavorites() }
    }

    // This manager is a singleton shared by MainActivity and SonosDreamService
    // (the screensaver), each with its own independent lifecycle calling
    // start/stopPolling. Reference-counted so one host detaching (e.g. the
    // screensaver dismissing, which happens routinely) doesn't kill the
    // shared poll loop out from under the other host - previously it did,
    // permanently freezing the Now Playing screen until the app was killed
    // and relaunched, since MainActivity only calls startPolling() once, in
    // onCreate, never again on resume.
    private var activeHostCount = 0

    fun startPolling() {
        activeHostCount++
        if (pollingJob != null) return
        scope.launch {
            _favoriteArtistCache.value = settingsManager.favoriteArtistCache.first()
            // The saved favorites show straight away, even with the
            // speaker offline, until the refresh below brings them up to date.
            shownKinds = Triple(
                settingsManager.filterAlbums.first(),
                settingsManager.filterPlaylists.first(),
                settingsManager.filterRadio.first()
            )
            savedLock.withLock { ensureSavedLoaded() }
            publishFavorites()

            reloadFavorites()
        }
        pollingJob = scope.launch {
            launch { pollSonos() }
            launch { pollHA() }
        }
    }

    fun stopPolling() {
        activeHostCount = (activeHostCount - 1).coerceAtLeast(0)
        if (activeHostCount > 0) return
        pollingJob?.cancel()
        pollingJob = null
        haEvents.stop()
        // The poll loop may be blocked in wait_for_change - wake it so it
        // sees the cancellation now, and drop the speaker subscriptions.
        scope.launch {
            try {
                val bridge = Python.getInstance().getModule("sonos_bridge")
                bridge.callAttr("notify_change")
                bridge.callAttr("stop_events")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun triggerDiscovery() {
        scope.launch {
            val py = Python.getInstance()
            val speakers = try {
                py.getModule("sonos_bridge")
                    .callAttr("discover_speakers")
                    .asList()
                    .map { it.toString() }
            } catch (e: Exception) {
                emptyList<String>()
            }
            _discoveredSpeakers.value = speakers
        }
    }

    fun refreshSpeakerGroups() {
        scope.launch {
            val py = Python.getInstance()
            val speaker = settingsManager.sonosSpeaker.first()
            if (speaker.isNotBlank()) {
                try {
                    val pyGroups = py.getModule("sonos_bridge")
                        .callAttr("get_speaker_groups", speaker)
                        .asList()
                    
                    val groups = pyGroups.map { 
                        val map = it.asMap()
                        SonosSpeakerGroup(
                            name = map[PyObject.fromJava("name")]?.toString() ?: "",
                            isJoined = map[PyObject.fromJava("is_joined")]?.toBoolean() ?: false,
                            ip = map[PyObject.fromJava("ip")]?.toString() ?: "",
                            model = map[PyObject.fromJava("model")]?.toString() ?: ""
                        )
                    }
                    _speakerGroups.value = groups
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // Speaker-hardware EQ/features rarely change, so this piggybacks on
    // refreshSpeakerGroups()'s cadence (every 5th poll cycle) rather than
    // hitting the speaker with extra UPnP calls every 2s.
    fun refreshAudioSettings() {
        scope.launch {
            val py = Python.getInstance()
            val speaker = settingsManager.sonosSpeaker.first()
            if (speaker.isNotBlank()) {
                try {
                    val map = py.getModule("sonos_bridge")
                        .callAttr("get_audio_settings", speaker)
                        .asMap()
                    if (!map.containsKey(PyObject.fromJava("error"))) {
                        // night_mode/dialog_mode/bass/treble/loudness come back as
                        // Python None on speakers that don't support them -
                        // toJava(the boxed type) is the Chaquopy-documented way to
                        // get a real Kotlin null out of that, rather than toBoolean()/
                        // toInt() which throw trying to convert None.
                        _audioSettings.value = SonosAudioSettings(
                            isSoundbar = map[PyObject.fromJava("is_soundbar")]?.toBoolean() ?: false,
                            hasSubwoofer = map[PyObject.fromJava("has_subwoofer")]?.toBoolean() ?: false,
                            nightMode = map[PyObject.fromJava("night_mode")]?.toJava(Boolean::class.javaObjectType),
                            dialogMode = map[PyObject.fromJava("dialog_mode")]?.toJava(Boolean::class.javaObjectType),
                            bass = map[PyObject.fromJava("bass")]?.toJava(Int::class.javaObjectType),
                            treble = map[PyObject.fromJava("treble")]?.toJava(Int::class.javaObjectType),
                            loudness = map[PyObject.fromJava("loudness")]?.toJava(Boolean::class.javaObjectType),
                            subEnabled = map[PyObject.fromJava("sub_enabled")]?.toJava(Boolean::class.javaObjectType)
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun toggleNightMode() {
        scope.launch {
            val speaker = settingsManager.sonosSpeaker.first()
            if (speaker.isNotBlank()) {
                val newValue = !(_audioSettings.value.nightMode ?: false)
                val py = Python.getInstance()
                val ok = py.getModule("sonos_bridge").callAttr("set_night_mode", newValue, speaker).toBoolean()
                if (ok) _audioSettings.update { it.copy(nightMode = newValue) }
            }
        }
    }

    fun toggleDialogMode() {
        scope.launch {
            val speaker = settingsManager.sonosSpeaker.first()
            if (speaker.isNotBlank()) {
                val newValue = !(_audioSettings.value.dialogMode ?: false)
                val py = Python.getInstance()
                val ok = py.getModule("sonos_bridge").callAttr("set_dialog_mode", newValue, speaker).toBoolean()
                if (ok) _audioSettings.update { it.copy(dialogMode = newValue) }
            }
        }
    }

    fun setBass(value: Int) {
        scope.launch {
            val speaker = settingsManager.sonosSpeaker.first()
            if (speaker.isNotBlank()) {
                val py = Python.getInstance()
                val ok = py.getModule("sonos_bridge").callAttr("set_bass", value, speaker).toBoolean()
                if (ok) _audioSettings.update { it.copy(bass = value) }
            }
        }
    }

    fun setTreble(value: Int) {
        scope.launch {
            val speaker = settingsManager.sonosSpeaker.first()
            if (speaker.isNotBlank()) {
                val py = Python.getInstance()
                val ok = py.getModule("sonos_bridge").callAttr("set_treble", value, speaker).toBoolean()
                if (ok) _audioSettings.update { it.copy(treble = value) }
            }
        }
    }

    fun setLoudness(enabled: Boolean) {
        scope.launch {
            val speaker = settingsManager.sonosSpeaker.first()
            if (speaker.isNotBlank()) {
                val py = Python.getInstance()
                val ok = py.getModule("sonos_bridge").callAttr("set_loudness", enabled, speaker).toBoolean()
                if (ok) _audioSettings.update { it.copy(loudness = enabled) }
            }
        }
    }

    fun toggleSubEnabled() {
        scope.launch {
            val speaker = settingsManager.sonosSpeaker.first()
            if (speaker.isNotBlank()) {
                val newValue = !(_audioSettings.value.subEnabled ?: false)
                val py = Python.getInstance()
                val ok = py.getModule("sonos_bridge").callAttr("set_sub_enabled", newValue, speaker).toBoolean()
                if (ok) _audioSettings.update { it.copy(subEnabled = newValue) }
            }
        }
    }

    fun toggleSpeakerJoin(otherSpeakerName: String, join: Boolean) {
        scope.launch {
            val py = Python.getInstance()
            val speaker = settingsManager.sonosSpeaker.first()
            if (speaker.isNotBlank()) {
                try {
                    py.getModule("sonos_bridge")
                        .callAttr("toggle_speaker_join", speaker, otherSpeakerName, join)
                    refreshSpeakerGroups()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // Cover Flow's refresh button: the saved speaker and filters.
    fun refreshSavedFavorites(onResult: (FavoritesRefreshResult) -> Unit) {
        scope.launch {
            refreshFavorites(
                settingsManager.filterAlbums.first(),
                settingsManager.filterPlaylists.first(),
                settingsManager.filterRadio.first(),
                onResult = onResult
            )
        }
    }

    // speakerOverride: Settings' unsaved pick (see SettingsModal's
    // onRefreshFavorites); null uses the saved speaker. Always every
    // favorite - Sonos caps them at about 70 anyway.
    // onResult, if given, hears how it went, on the main thread.
    fun refreshFavorites(
        fAlbums: Boolean,
        fPlaylists: Boolean,
        fRadio: Boolean,
        speakerOverride: String? = null,
        onResult: ((FavoritesRefreshResult) -> Unit)? = null
    ) {
        fun report(result: FavoritesRefreshResult) {
            if (onResult != null) scope.launch(Dispatchers.Main) { onResult(result) }
        }
        scope.launch {
            _favoritesRefreshStatus.value = "Starting Instant Scan..."
            _favoritesRefreshCount.value = null
            val py = Python.getInstance()
            val pyModule = py.getModule("sonos_bridge")
            val speaker = speakerOverride?.takeIf { it.isNotBlank() } ?: settingsManager.sonosSpeaker.first()

            if (speaker.isBlank()) {
                _favoritesRefreshStatus.value = "Error: Select a speaker first"
                report(FavoritesRefreshResult(0, 0, "Select a speaker first"))
                return@launch
            }

            try {
                // Always every kind: the saved list holds them all, and
                // something filtered out here mustn't look removed from
                // Sonos. The category filters only pick what Cover Flow shows.
                val pyFavs = withTimeoutOrNull(FAVORITES_FETCH_TIMEOUT_MS) {
                    pyModule.callAttr("get_sonos_favorites", speaker, "albums,playlists,radio").asList()
                }

                if (pyFavs == null) {
                    _favoritesRefreshStatus.value = "Error: Speaker timed out"
                    report(FavoritesRefreshResult(0, _albumHistory.value.size, "Speaker timed out"))
                    return@launch
                }

                val favItems = mutableListOf<AlbumHistoryItem>()
                var diagMsg = ""

                for (item in pyFavs) {
                    val map = item.asMap()
                    val title = map[PyObject.fromJava("title")]?.toString() ?: ""

                    if (title == "DIAGNOSTIC" || title == "DIAG") {
                        diagMsg = map[PyObject.fromJava("artist")]?.toString() ?: ""
                    } else {
                        favItems.add(AlbumHistoryItem(
                            artUrl = map[PyObject.fromJava("art")]?.toString() ?: "",
                            artist = map[PyObject.fromJava("artist")]?.toString() ?: "Unknown Artist",
                            albumTitle = title,
                            playUri = map[PyObject.fromJava("uri")]?.toString() ?: "",
                            playMetadata = map[PyObject.fromJava("meta")]?.toString() ?: "",
                            kind = map[PyObject.fromJava("kind")]?.toString() ?: "",
                            albumKey = map[PyObject.fromJava("album_key")]?.toString() ?: "",
                            genre = map[PyObject.fromJava("genre")]?.toString() ?: "",
                            replay = map[PyObject.fromJava("replay")]?.toString() ?: ""
                        ))
                    }
                }

                // "No items matched" is Sonos genuinely having no favorites;
                // any other diagnostic (speaker offline, crawl error) means
                // the list couldn't be read, so nothing gets marked as gone.
                if (favItems.isEmpty() && diagMsg.isNotBlank() && !diagMsg.startsWith("No items matched")) {
                    _favoritesRefreshStatus.value = "Error: $diagMsg"
                    report(FavoritesRefreshResult(0, _albumHistory.value.size, diagMsg))
                    return@launch
                }

                // New = not among the favorites shown before the refresh.
                val before = _albumHistory.value.map { favoriteKey(it) }.toSet()
                shownKinds = Triple(fAlbums, fPlaylists, fRadio)
                savedLock.withLock {
                    ensureSavedLoaded()
                    updateSaved(mergeSavedFavorites(_savedFavorites.value, favItems))
                }
                publishFavorites()
                val shown = _albumHistory.value
                val added = shown.count { favoriteKey(it) !in before }
                val localOnly = _savedFavorites.value.count { !it.onSonos }
                _favoritesRefreshCount.value = shown.size
                report(FavoritesRefreshResult(added, shown.size))
                _favoritesRefreshStatus.value = "Success: Found ${favItems.size} on Sonos" +
                    if (localOnly > 0) ", $localOnly saved in app only" else ""
            } catch (e: Exception) {
                _favoritesRefreshStatus.value = "Critical Error: ${e.message}"
                report(FavoritesRefreshResult(0, _albumHistory.value.size, e.message ?: "Unknown error"))
            }
        }
    }

    // Callers hold savedLock.
    private fun ensureSavedLoaded() {
        if (savedLoaded) return
        _savedFavorites.value = savedFavoritesStore.load()
        savedLoaded = true
    }

    // Callers hold savedLock.
    private fun updateSaved(items: List<AlbumHistoryItem>) {
        val sorted = items.sortedBy { it.albumTitle.lowercase() }
        _savedFavorites.value = sorted
        savedFavoritesStore.save(sorted)
    }

    // Rebuilds Cover Flow's list (the shown kinds) and the album list
    // (every album, whatever the filters) from the saved favorites.
    private fun publishFavorites() {
        val saved = _savedFavorites.value
        val (albums, playlists, radio) = shownKinds
        val shown = saved.filter {
            when (it.kind) {
                "playlist" -> playlists
                "radio" -> radio
                else -> albums
            }
        }
        _favoriteAlbumsRef.value = withResolvedArtists(saved.filter { it.kind == "album" })
        _albumHistory.value = withResolvedArtists(shown)
    }

    // Album-title comparison key that ignores edition/version suffixes in
    // brackets ("(Deluxe Version)", "[Remastered 2009]") plus case and extra
    // whitespace. Falls back to the plain lowercased title if stripping would
    // leave nothing (a title that's entirely in brackets).
    private fun normalizeAlbumTitle(title: String): String {
        val stripped = title
            .replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .lowercase()
        return stripped.ifEmpty { title.trim().lowercase() }
    }

    // What get_sonos_favorites reports when it doesn't know the artist.
    private fun isPlaceholderArtist(artist: String) =
        artist.isBlank() || artist == "Unknown Artist" || artist == "Sonos Favorite"

    private fun artistCacheKey(albumKey: String) = "id:$albumKey"

    // Whether two favorites are the same album - by service ID when both
    // have one, since a title can be shared ("MTV Unplugged (Live)").
    private fun sameFavorite(a: AlbumHistoryItem, b: AlbumHistoryItem) =
        if (a.albumKey.isNotBlank() && b.albumKey.isNotBlank()) a.albumKey == b.albumKey
        else normalizeAlbumTitle(a.albumTitle) == normalizeAlbumTitle(b.albumTitle)

    // Fills in each favorite's best known artist: what the sync itself found
    // (Apple Music albums are looked up by catalog ID), else one learned by
    // watching that exact album play. Older title-keyed learned names (some
    // keyed by the full lowercased title, brackets and all) are only trusted
    // for a title no other favorite shares - otherwise e.g. both "MTV
    // Unplugged (Live)" albums would show the same artist.
    private fun withResolvedArtists(items: List<AlbumHistoryItem>): List<AlbumHistoryItem> {
        val cache = _favoriteArtistCache.value
        val titleCounts = (items + _favoriteAlbumsRef.value)
            .distinctBy { it.albumKey.ifBlank { it.playUri } }
            .groupingBy { normalizeAlbumTitle(it.albumTitle) }
            .eachCount()
        return items.map { item ->
            if (!isPlaceholderArtist(item.artist)) return@map item
            val learned = item.albumKey.takeIf { it.isNotBlank() }?.let { cache[artistCacheKey(it)] }
                ?: if ((titleCounts[normalizeAlbumTitle(item.albumTitle)] ?: 0) <= 1) {
                    cache[normalizeAlbumTitle(item.albumTitle)] ?: cache[item.albumTitle.lowercase()]
                } else null
            if (learned != null) item.copy(artist = learned) else item
        }
    }

    // Which of the favorites matching the playing album's title is the one
    // actually playing - null when that can't be told, so one album's
    // artist is never stamped onto another that merely shares its title.
    private fun pickPlayingFavorite(candidates: List<AlbumHistoryItem>, playingArtist: String): AlbumHistoryItem? {
        if (candidates.size <= 1) return candidates.firstOrNull()
        candidates.find { it.artist.equals(playingArtist, ignoreCase = true) }?.let { return it }
        lastStartedFavorite?.let { (started, atMs) ->
            if (System.currentTimeMillis() - atMs < LAST_STARTED_FAVORITE_TRUST_MS) {
                candidates.find { sameFavorite(it, started) }?.let { return it }
            }
        }
        // Every other one already has a different known artist.
        return candidates.singleOrNull { isPlaceholderArtist(it.artist) }
    }

    // SoCo reports position/duration as "H:MM:SS" - live streams/radio (and
    // anything else it can't time) come back as "NOT_IMPLEMENTED" or blank,
    // which just falls through to 0 here rather than a parse crash.
    private fun parseSonosTime(raw: String?): Int {
        val parts = raw?.split(":") ?: return 0
        if (parts.size != 3) return 0
        val hours = parts[0].toIntOrNull() ?: return 0
        val minutes = parts[1].toIntOrNull() ?: return 0
        val seconds = parts[2].toIntOrNull() ?: return 0
        return hours * 3600 + minutes * 60 + seconds
    }

    private suspend fun pollSonos() {
        val py = Python.getInstance()
        val pyModule = py.getModule("sonos_bridge")

        var lastFavoritesRefreshMs = System.currentTimeMillis()
        var lastGroupsRefreshMs = 0L

        while (currentCoroutineContext().isActive) {
            try {
                val speaker = settingsManager.sonosSpeaker.first()
                if (speaker.isNotBlank()) {
                    val now = System.currentTimeMillis()
                    if (now - lastFavoritesRefreshMs >= FAVORITES_REFRESH_INTERVAL_MS) {
                        // Hourly. Timed, not counted in loops, since the
                        // loop runs as often as events arrive.
                        reloadFavorites()
                        lastFavoritesRefreshMs = now
                    }

                    if (now - lastGroupsRefreshMs >= GROUPS_REFRESH_INTERVAL_MS) {
                        refreshSpeakerGroups()
                        refreshAudioSettings()
                        lastGroupsRefreshMs = now
                    }

                    val pyDict = pyModule.callAttr("get_now_playing", speaker).asMap()

                    if (!pyDict.containsKey(PyObject.fromJava("error"))) {
                        val tvDetected = pyDict[PyObject.fromJava("is_tv")]?.toBoolean() ?: false
                        val vol = pyDict[PyObject.fromJava("volume")]?.toString()?.toFloatOrNull() ?: 30f
                        val muted = pyDict[PyObject.fromJava("mute")]?.toBoolean() ?: false
                        val grouped = pyDict[PyObject.fromJava("is_grouped")]?.toBoolean() ?: false

                        _isTvMode.value = tvDetected
                        _currentVolume.value = vol
                        _trackState.update { it.copy(isMuted = muted, isGrouped = grouped) }

                        val groupList = pyDict[PyObject.fromJava("group_members")]?.asList() ?: emptyList()
                        val memberVolumes = groupList.map { 
                            val m = it.asMap()
                            GroupMemberVolume(
                                name = m[PyObject.fromJava("name")]?.toString() ?: "",
                                volume = m[PyObject.fromJava("volume")]?.toString()?.toFloatOrNull() ?: 0f,
                                isCoordinator = m[PyObject.fromJava("is_coordinator")]?.toBoolean() ?: false,
                                model = m[PyObject.fromJava("model")]?.toString() ?: ""
                            )
                        }

                        if (tvDetected) {
                            val url = settingsManager.haUrl.first()
                            val token = settingsManager.haToken.first()
                            val haMedia = settingsManager.selectedHaMediaPlayer.first()
                            val autoUnmute = settingsManager.autoUnmuteAfterAds.first()
                            val tmdbKey = settingsManager.tmdbApiKey.first()
                            val tvShowImages = settingsManager.tvShowImages.first()
                            val youTubeThumbs = settingsManager.youTubeThumbnails.first()
                            val youTubeKey = settingsManager.youTubeApiKey.first()
                            val youTubeQuotaReached = youTubeThumbs && settingsManager.youTubeUsage.first().quotaReached

                            if (haMedia.isNotBlank()) {
                                haManager.fetchHAMediaPlayer(url, token, haMedia) { haTrack ->
                                    maybeAutoUnmuteAfterAds(haTrack, speaker, muted, autoUnmute)
                                    val showLogo = if (tvShowImages) tvShowLogoFor(haTrack, tmdbKey) else null
                                    val art = haTrack.albumArtUrl.ifBlank {
                                        if (youTubeThumbs) youTubeThumbnailFor(haTrack, youTubeKey, youTubeQuotaReached) ?: "" else ""
                                    }
                                    _trackState.update { it.copy(
                                        title = haTrack.title,
                                        artist = haTrack.artist,
                                        album = haTrack.album,
                                        albumArtUrl = art,
                                        seriesTitle = haTrack.seriesTitle,
                                        tvShowLogo = showLogo,
                                        isPlaying = haTrack.isPlaying,
                                        prevTrack = "None",
                                        nextTrack = "None",
                                        groupMembers = memberVolumes,
                                        positionSeconds = haTrack.positionSeconds,
                                        durationSeconds = haTrack.durationSeconds,
                                        shuffleAvailable = false,
                                        appName = haTrack.appName,
                                        appId = haTrack.appId,
                                        contentId = haTrack.contentId,
                                        contentType = haTrack.contentType,
                                        haAttributes = haTrack.haAttributes
                                    )}
                                }
                            } else {
                                _trackState.update { it.copy(
                                    title = "TV Mode",
                                    artist = "Select HA Entity",
                                    albumArtUrl = "",
                                    seriesTitle = "",
                                    tvShowLogo = null,
                                    isPlaying = true,
                                    groupMembers = memberVolumes,
                                    positionSeconds = 0,
                                    durationSeconds = 0,
                                    shuffleAvailable = false,
                                    appName = "",
                                    appId = "",
                                    contentId = "",
                                    contentType = "",
                                    haAttributes = ""
                                )}
                            }
                        } else {
                            lastTvRemainingSeconds = null
                            val title = pyDict[PyObject.fromJava("title")]?.toString() ?: ""
                            val artist = pyDict[PyObject.fromJava("artist")]?.toString() ?: ""
                            val album = pyDict[PyObject.fromJava("album")]?.toString() ?: ""
                            val art = pyDict[PyObject.fromJava("album_art")]?.toString() ?: ""
                            val state = pyDict[PyObject.fromJava("state")]?.toString() ?: "STOPPED"
                            val position = parseSonosTime(pyDict[PyObject.fromJava("position")]?.toString())
                            val duration = parseSonosTime(pyDict[PyObject.fromJava("duration")]?.toString())
                            val fromQueue = pyDict[PyObject.fromJava("shuffle_available")]?.toBoolean() ?: false
                            val shuffled = pyDict[PyObject.fromJava("shuffle")]?.toBoolean() ?: false
                            val onLastTrack = pyDict[PyObject.fromJava("on_last_track")]?.toBoolean() ?: false
                            maybeContinueWithRandomAlbum(
                                state, fromQueue, shuffled, onLastTrack, position, duration,
                                settingsManager.continueWithRandomAlbum.first()
                            )
                            // "None" is TrackState's existing "nothing to show"
                            // marker for these, which the UI checks for.
                            fun pyString(key: String) = pyDict[PyObject.fromJava(key)]?.toString() ?: ""
                            val nextTitle = pyString("next_title").ifBlank { "None" }

                            // Sonos briefly reports a blank title while
                            // switching tracks or albums, so only a real,
                            // different song counts as a change. Compared
                            // without the art URL, which can change for the
                            // same song.
                            if (title.isNotBlank()) {
                                val nowPlaying = TrackDetails(title = title, artist = artist, album = album, artUrl = art)
                                val previouslySeen = lastSeenTrack
                                if (previouslySeen != null && previouslySeen.copy(artUrl = "") != nowPlaying.copy(artUrl = "")) {
                                    lastPlayedTrack = previouslySeen
                                }
                                lastSeenTrack = nowPlaying
                            }
                            // Until a song change has been seen (e.g. right
                            // after the app starts), fall back to the track
                            // before this one in the queue.
                            val previous = lastPlayedTrack ?: TrackDetails(
                                title = pyString("prev_title").ifBlank { "None" },
                                artist = pyString("prev_artist"),
                                album = pyString("prev_album"),
                                artUrl = pyString("prev_art")
                            )

                            _trackState.update { it.copy(
                                title = title,
                                artist = artist,
                                album = album,
                                albumArtUrl = art,
                                seriesTitle = "",
                                tvShowLogo = null,
                                appName = "",
                                appId = "",
                                contentId = "",
                                contentType = "",
                                haAttributes = "",
                                isPlaying = state == "PLAYING",
                                groupMembers = memberVolumes,
                                positionSeconds = position,
                                durationSeconds = duration,
                                shuffleAvailable = fromQueue,
                                isShuffled = shuffled,
                                repeatMode = pyDict[PyObject.fromJava("repeat")]?.toString() ?: "OFF",
                                prevTrack = previous.title,
                                prevArtist = previous.artist,
                                prevAlbum = previous.album,
                                prevArtUrl = previous.artUrl,
                                nextTrack = nextTitle,
                                nextArtist = pyString("next_artist"),
                                nextArtUrl = pyString("next_art")
                            )}

                            if (artist.isNotBlank() && album.isNotBlank()) {
                                // Matching gives us a real Sonos Favorite ID to
                                // replay later, instead of an unreplayable
                                // snapshot of whatever track happened to be
                                // playing. Sonos's favorite data for albums
                                // never includes an artist field (only
                                // title/resource/class), so match on album
                                // title alone and take the real artist name
                                // from the live playback info instead.
                                //
                                // The playing track's album name often carries
                                // an edition suffix the favorite doesn't (or
                                // vice versa) - "(Deluxe Version)",
                                // "[Remastered]" - so fall back to comparing
                                // with those stripped when there's no exact
                                // match. Exact still wins, so "Album" and
                                // "Album (Live)" favorites stay distinct.
                                //
                                // Several favorites can share a title (Pearl
                                // Jam's and Alice in Chains' "MTV Unplugged
                                // (Live)") - see pickPlayingFavorite.
                                val normalizedAlbum = normalizeAlbumTitle(album)
                                val favorites = _favoriteAlbumsRef.value
                                val match = pickPlayingFavorite(
                                    favorites.filter { it.albumTitle.equals(album, ignoreCase = true) }
                                        .ifEmpty { favorites.filter { normalizeAlbumTitle(it.albumTitle) == normalizedAlbum } },
                                    artist
                                )
                                if (match != null) {
                                    // Keyed by the album's service ID, not
                                    // playUri: Sonos favorite IDs ("FV:2/<n>")
                                    // aren't guaranteed stable across
                                    // refreshes, so a playUri-keyed cache could
                                    // "forget" an already-learned artist.
                                    val cacheKey = if (match.albumKey.isNotBlank()) artistCacheKey(match.albumKey)
                                        else normalizeAlbumTitle(match.albumTitle)

                                    // So Cover Flow can show the real name
                                    // instead of the generic placeholder.
                                    if (_favoriteArtistCache.value[cacheKey] != artist) {
                                        val updatedCache = _favoriteArtistCache.value + (cacheKey to artist)
                                        _favoriteArtistCache.value = updatedCache
                                        settingsManager.saveFavoriteArtistCache(updatedCache)
                                    }
                                    if (match.artist != artist) {
                                        _favoriteAlbumsRef.value = favorites.map { if (it === match) it.copy(artist = artist) else it }
                                    }

                                    // Show it in Favorites straight away rather
                                    // than waiting for the next favorites refresh
                                    // to pick it up from the cache.
                                    if (_albumHistory.value.any { sameFavorite(it, match) && it.artist != artist }) {
                                        _albumHistory.value = _albumHistory.value.map {
                                            if (sameFavorite(it, match)) it.copy(artist = artist) else it
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            waitForSonosChange(pyModule)
        }
    }

    // True while speaker events are confirmed to be arriving - shown in the
    // Metadata Debugger.
    private val _instantUpdates = MutableStateFlow(false)
    val instantUpdates: StateFlow<Boolean> = _instantUpdates.asStateFlow()

    // Sleeps until the next refresh is due: straight away when the speaker
    // reports a change (see ensure_events in sonos_bridge.py), otherwise
    // after a fallback interval - long once events are known to be flowing,
    // the old 2s/6s poll when they aren't (or in TV mode, whose title, art
    // and position come from Home Assistant rather than speaker events).
    private suspend fun waitForSonosChange(pyModule: PyObject) {
        val speaker = settingsManager.sonosSpeaker.first()
        val eventsActive = speaker.isNotBlank() && try {
            pyModule.callAttr("ensure_events", speaker).toBoolean()
        } catch (e: Exception) {
            false
        }
        _instantUpdates.value = eventsActive
        val idle = _isIdleScreen.value
        val waitMs = if (eventsActive && !_isTvMode.value) {
            if (idle) IDLE_EVENT_FALLBACK_MS else ACTIVE_EVENT_FALLBACK_MS
        } else {
            if (idle) IDLE_POLL_INTERVAL_MS else ACTIVE_POLL_INTERVAL_MS
        }
        try {
            pyModule.callAttr("wait_for_change", waitMs / 1000.0)
        } catch (e: Exception) {
            delay(waitMs)
        }
    }

    private val tvShowLogos = TvShowLogos()

    // The logo for the TV show in this HA track, if one's recognised and
    // already looked up. Otherwise starts the lookup and fills it in once
    // it arrives - if that show is still on by then.
    private fun tvShowLogoFor(track: TrackState, tmdbKey: String): TvShowLogo? {
        val show = detectTvShowName(track.title, track.seriesTitle, track.artist, track.album) ?: return null
        tvShowLogos.cached(show, tmdbKey)?.let { return it }
        if (tvShowLogos.claimLookup(show, tmdbKey)) {
            scope.launch {
                val logo = tvShowLogos.lookUp(show, tmdbKey) ?: return@launch
                _trackState.update {
                    if (_isTvMode.value && detectTvShowName(it.title, it.seriesTitle, it.artist, it.album) == show) it.copy(tvShowLogo = logo) else it
                }
            }
        }
        return null
    }

    private val youTubeThumbnails = YouTubeThumbnails { result, query ->
        scope.launch { settingsManager.recordYouTubeSearch(result, query) }
    }

    // The thumbnail for the YouTube video in this HA track, if it's already
    // been found. Otherwise starts the lookup and fills it in once it
    // arrives - if that video is still on, still without artwork, by then.
    // Once Google says the day's quota is used up, no more searches are
    // sent until it resets (a known video ID still works).
    private fun youTubeThumbnailFor(track: TrackState, apiKey: String, quotaReached: Boolean): String? {
        if (!YouTubeThumbnails.isYouTube(track)) return null
        youTubeThumbnails.cached(track, apiKey)?.let { return it }
        val searchBlocked = quotaReached && YouTubeThumbnails.videoIdFrom(track.contentId) == null
        if (!searchBlocked && youTubeThumbnails.claimLookup(track, apiKey)) {
            scope.launch {
                val thumbnail = youTubeThumbnails.lookUp(track, apiKey) ?: return@launch
                _trackState.update {
                    val sameVideo = it.title == track.title && it.artist == track.artist && it.contentId == track.contentId
                    if (_isTvMode.value && sameVideo && it.albumArtUrl.isBlank()) it.copy(albumArtUrl = thumbnail) else it
                }
            }
        }
        return null
    }

    // The configured unit rarely/never changes at runtime, so fetch it once
    // per url/token rather than on every 5s poll cycle.
    private var cachedTemperatureUnit = "°C"
    private var cachedTemperatureUnitFor = ""

    private val haEvents = HomeAssistantEvents()
    val haInstantUpdates: Boolean get() = haEvents.isReceiving
    // Woken by Home Assistant pushing a change to a widget's entity, or by
    // the widget list changing in Settings.
    private val haWake = Channel<Unit>(Channel.CONFLATED)

    // Each weather widget's forecasts, refreshed every FORECAST_REFRESH_MS
    // rather than on every state change - they're service calls, and
    // forecasts only move slowly.
    private data class CachedForecasts(val forecasts: HaWeatherForecasts, val fetchedAt: Long)
    private val forecastCache = java.util.concurrent.ConcurrentHashMap<String, CachedForecasts>()
    private val forecastsLoading = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun refreshForecastsIfStale(url: String, token: String, weather: HaWeatherState) {
        val id = weather.entityId
        val cached = forecastCache[id]
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < FORECAST_REFRESH_MS) return
        val types = weather.forecastTypes
        if (types.isEmpty() || !forecastsLoading.add(id)) return
        val results = java.util.concurrent.ConcurrentHashMap<String, List<HaForecast>>()
        val remaining = java.util.concurrent.atomic.AtomicInteger(types.size)
        types.forEach { type ->
            haManager.fetchWeatherForecast(url, token, id, type) { forecasts ->
                results[type] = forecasts
                if (remaining.decrementAndGet() > 0) return@fetchWeatherForecast
                val loaded = HaWeatherForecasts(
                    hourly = results["hourly"].orEmpty(),
                    daily = results["daily"].orEmpty(),
                    twiceDaily = results["twice_daily"].orEmpty()
                )
                // A failed fetch keeps whatever was there and tries again
                // sooner, rather than wiping the forecast.
                val now = System.currentTimeMillis()
                forecastCache[id] = if (loaded.isEmpty) {
                    CachedForecasts(cached?.forecasts ?: loaded, now - FORECAST_REFRESH_MS + FORECAST_RETRY_MS)
                } else {
                    CachedForecasts(loaded, now)
                }
                forecastsLoading.remove(id)
                _haState.update { old -> old.copy(entities = old.entities.mapValues { (_, e) -> withForecasts(e, old.entities) }) }
            }
        }
    }

    // Fills a weather entity's forecasts (from the cache, else whatever its
    // state carried) and the sun's position; anything else passes through.
    private fun withForecasts(entity: HaEntityState, all: Map<String, HaEntityState>): HaEntityState {
        if (entity !is HaWeatherState) return entity
        val cached = forecastCache[entity.entityId]?.forecasts?.takeUnless { it.isEmpty }
        return entity.copy(
            forecasts = cached ?: entity.forecasts,
            sun = all[SUN_ENTITY] as? HaSunState
        )
    }

    private suspend fun pollHA() {
        coroutineScope {
            launch { settingsManager.haWidgets.distinctUntilChanged().drop(1).collect { haWake.trySend(Unit) } }
            pollHALoop()
        }
    }

    private suspend fun pollHALoop() {
        while (currentCoroutineContext().isActive) {
            val url = settingsManager.haUrl.first()
            val token = settingsManager.haToken.first()
            val widgets = settingsManager.haWidgets.first().filterNotNull()
            val widgetEntities = widgets.map { it.entityId }.distinct()
            // The sun comes along with any weather widget, for night icons.
            val stateEntities = if (widgetEntities.any { it.startsWith("weather.") }) widgetEntities + SUN_ENTITY else widgetEntities
            val haMedia = settingsManager.selectedHaMediaPlayer.first()
            haEvents.ensure(url, token, stateEntities + haMedia) { entityId ->
                if (entityId == haMedia) {
                    // TV mode's title/art/position come from this entity,
                    // fetched by the Sonos loop - wake that one instead.
                    if (_isTvMode.value) {
                        try {
                            Python.getInstance().getModule("sonos_bridge").callAttr("notify_change")
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                } else {
                    haWake.trySend(Unit)
                }
            }
            if (url.isNotBlank() && token.isNotBlank()) {
                val cacheKey = "$url|$token"
                if (cachedTemperatureUnitFor != cacheKey) {
                    haManager.fetchTemperatureUnit(url, token) { unit ->
                        cachedTemperatureUnit = unit
                        cachedTemperatureUnitFor = cacheKey
                    }
                }
                haManager.fetchEntityStates(url, token, stateEntities, cachedTemperatureUnit) { entities ->
                    // Keep the last known state of anything that failed to
                    // load this time, so a dropped request doesn't make its
                    // widget blink out.
                    _haState.update { old ->
                        val merged = stateEntities.mapNotNull { id ->
                            (entities[id] ?: old.entities[id])?.let { id to it }
                        }.toMap()
                        HAState(widgets = widgets, entities = merged.mapValues { (_, e) -> withForecasts(e, merged) })
                    }
                    entities.values.filterIsInstance<HaWeatherState>().forEach { refreshForecastsIfStale(url, token, it) }
                }
            } else {
                _haState.value = HAState()
            }
            // Pushed changes refresh right away; the timed refresh is only a
            // fallback, so it can be much slower while the WebSocket is live.
            val idle = _isIdleScreen.value
            val waitMs = if (haEvents.isReceiving) {
                if (idle) IDLE_EVENT_FALLBACK_MS * 2 else ACTIVE_EVENT_FALLBACK_MS * 3
            } else {
                if (idle) IDLE_HA_POLL_INTERVAL_MS else ACTIVE_HA_POLL_INTERVAL_MS
            }
            withTimeoutOrNull(waitMs) { haWake.receive() }
        }
    }

    // Now Playing's drag-to-reorder: newOrder is the widgets it shows, in
    // their new order. They take over the Settings positions those widgets
    // held, so empty positions and widgets not shown yet (entity still
    // loading) stay where they were.
    fun reorderHaWidgets(newOrder: List<HaWidgetSlot>) {
        // Shown in the new order straight away, before the save lands.
        _haState.update { it.copy(widgets = newOrder + it.widgets.filter { w -> w !in newOrder }) }
        scope.launch {
            val positions = settingsManager.haWidgets.first()
            // Which positions belong to the reordered widgets is decided
            // against the full set of them (one match per widget, so a
            // widget set up twice keeps both positions) - not against
            // what's left to place, which made a swapped widget's old
            // position keep it and so copied it instead of moving it.
            val unmatched = newOrder.toMutableList()
            val toPlace = ArrayDeque(newOrder)
            val updated = positions.map { slot ->
                if (slot != null && unmatched.remove(slot)) toPlace.removeFirst() else slot
            }
            settingsManager.saveHaWidgets(updated)
        }
    }

    // From Now Playing's Home Assistant widgets and their dialogs. Each
    // command names its own entity, since several widgets can share a type.
    // onResult (lock and alarm pop-ups) hears HA's answer, on the main
    // thread - other commands report success as soon as they're sent.
    fun sendHaCommand(command: HaCommand, onResult: ((HaServiceResult) -> Unit)? = null) {
        val answer: ((HaServiceResult) -> Unit)? = onResult?.let { callback ->
            { result -> scope.launch(Dispatchers.Main) { callback(result) } }
        }
        scope.launch {
            val url = settingsManager.haUrl.first()
            val token = settingsManager.haToken.first()
            val id = command.entityId
            var waitsForAnswer = false
            when (command) {
                is HaCommand.SetClimateTemperature -> haManager.setClimateTemperature(url, token, id, command.temp, command.high, command.low)
                is HaCommand.SetHvacMode -> haManager.setClimateHvacMode(url, token, id, command.mode)
                is HaCommand.SetFanMode -> haManager.setClimateFanMode(url, token, id, command.mode)
                is HaCommand.SetPresetMode -> haManager.setClimatePresetMode(url, token, id, command.mode)
                is HaCommand.SetLightOn -> haManager.setLightOn(url, token, id, command.on)
                is HaCommand.SetLightBrightness -> haManager.setLightBrightness(url, token, id, command.brightness)
                is HaCommand.SetLightRgb -> haManager.setLightColorRgb(url, token, id, command.r, command.g, command.b)
                is HaCommand.SetLightColorTemp -> haManager.setLightColorTempKelvin(url, token, id, command.kelvin)
                is HaCommand.OpenCover -> haManager.openCover(url, token, id)
                is HaCommand.CloseCover -> haManager.closeCover(url, token, id)
                is HaCommand.StopCover -> haManager.stopCover(url, token, id)
                is HaCommand.SetCoverPosition -> haManager.setCoverPosition(url, token, id, command.position)
                is HaCommand.Lock -> {
                    waitsForAnswer = true
                    haManager.lock(url, token, id, command.code, answer)
                }
                is HaCommand.Unlock -> {
                    waitsForAnswer = true
                    haManager.unlock(url, token, id, command.code, answer)
                }
                is HaCommand.ArmAlarm -> {
                    waitsForAnswer = true
                    haManager.armAlarm(url, token, id, command.mode, command.code, answer)
                }
                is HaCommand.DisarmAlarm -> {
                    waitsForAnswer = true
                    haManager.disarmAlarm(url, token, id, command.code, answer)
                }
                is HaCommand.RunScript -> haManager.runScript(url, token, id)
                is HaCommand.ActivateScene -> haManager.activateScene(url, token, id)
                is HaCommand.ToggleInputBoolean -> haManager.toggleInputBoolean(url, token, id)
                is HaCommand.SetFanOn -> haManager.setFanOn(url, token, id, command.on)
                is HaCommand.SetFanPercentage -> haManager.setFanPercentage(url, token, id, command.percentage)
                is HaCommand.SetFanOscillating -> haManager.setFanOscillating(url, token, id, command.oscillating)
                is HaCommand.SetFanDirection -> haManager.setFanDirection(url, token, id, command.direction)
                is HaCommand.SetFanPreset -> haManager.setFanPreset(url, token, id, command.mode)
            }
            if (!waitsForAnswer) answer?.invoke(HaServiceResult.Success)
        }
    }

    fun sendSonosAction(action: String) {
        scope.launch {
            // TV mode's transport (play/pause/next/previous) belongs to
            // whatever's actually feeding the TV input - the HA media_player
            // entity, not the Sonos speaker itself, which is just relaying
            // audio from the optical/HDMI input and has no real "next track"
            // to send. Same play/pause/next/previous button labels, different
            // backend, exactly like Now Playing already swaps its data source.
            if (_isTvMode.value) {
                val url = settingsManager.haUrl.first()
                val token = settingsManager.haToken.first()
                val haMedia = settingsManager.selectedHaMediaPlayer.first()
                if (haMedia.isNotBlank()) {
                    _trackState.update { it.copy(lastCommandSent = "HA Action: $action") }
                    haManager.sendMediaPlayerCommand(url, token, haMedia, action)
                }
            } else {
                val py = Python.getInstance()
                val speaker = settingsManager.sonosSpeaker.first()
                if (speaker.isNotBlank()) {
                    _trackState.update { it.copy(lastCommandSent = "Action: $action") }
                    py.getModule("sonos_bridge").callAttr("control_playback", action, speaker)
                }
            }
        }
    }

    fun toggleShuffle() {
        scope.launch {
            val speaker = settingsManager.sonosSpeaker.first()
            if (speaker.isBlank()) return@launch
            val shuffled = !_trackState.value.isShuffled
            _trackState.update { it.copy(lastCommandSent = "Action: shuffle ${if (shuffled) "on" else "off"}") }
            val ok = Python.getInstance().getModule("sonos_bridge")
                .callAttr("set_shuffle", shuffled, speaker).toBoolean()
            // Reflect it right away rather than waiting up to 2s for the
            // next poll to report it.
            if (ok) _trackState.update { it.copy(isShuffled = shuffled) }
        }
    }

    // Off -> All -> One -> Off, like the Sonos app.
    fun cycleRepeat() {
        scope.launch {
            val speaker = settingsManager.sonosSpeaker.first()
            if (speaker.isBlank()) return@launch
            val next = when (_trackState.value.repeatMode) {
                "OFF" -> "ALL"
                "ALL" -> "ONE"
                else -> "OFF"
            }
            _trackState.update { it.copy(lastCommandSent = "Action: repeat $next") }
            val ok = Python.getInstance().getModule("sonos_bridge")
                .callAttr("set_repeat", next, speaker).toBoolean()
            if (ok) _trackState.update { it.copy(repeatMode = next) }
        }
    }

    // Now Playing's song picker. Null when the speaker couldn't be read.
    suspend fun loadQueue(): SonosQueue? = withContext(Dispatchers.IO) {
        val speaker = settingsManager.sonosSpeaker.first()
        if (speaker.isBlank()) return@withContext null
        try {
            val result = Python.getInstance().getModule("sonos_bridge")
                .callAttr("get_queue", speaker).asMap()
            val error = result[PyObject.fromJava("error")]
            if (error != null) {
                Log.w("SonosDashboard", "Queue: $error")
                return@withContext null
            }
            val tracks = result[PyObject.fromJava("tracks")]?.asList().orEmpty().map { item ->
                val map = item.asMap()
                QueueTrack(
                    title = map[PyObject.fromJava("title")]?.toString() ?: "",
                    artist = map[PyObject.fromJava("artist")]?.toString() ?: "",
                    duration = map[PyObject.fromJava("duration")]?.toString() ?: ""
                )
            }
            SonosQueue(tracks, result[PyObject.fromJava("current")]?.toInt() ?: -1)
        } catch (e: Exception) {
            Log.w("SonosDashboard", "Queue failed: ${e.message}")
            null
        }
    }

    fun playQueueTrack(index: Int) {
        scope.launch {
            val speaker = settingsManager.sonosSpeaker.first()
            if (speaker.isBlank()) return@launch
            _trackState.update { it.copy(lastCommandSent = "Action: play queue track ${index + 1}") }
            Python.getInstance().getModule("sonos_bridge").callAttr("play_queue_track", index, speaker)
        }
    }

    // From tapping/dragging the progress bar. TV mode seeks the HA media
    // player feeding the TV instead, like the other transport controls.
    fun seekTo(positionSeconds: Int) {
        scope.launch {
            _trackState.update { it.copy(positionSeconds = positionSeconds, lastCommandSent = "Action: seek ${positionSeconds}s") }
            if (_isTvMode.value) {
                val haMedia = settingsManager.selectedHaMediaPlayer.first()
                if (haMedia.isNotBlank()) {
                    haManager.seekMediaPlayer(settingsManager.haUrl.first(), settingsManager.haToken.first(), haMedia, positionSeconds)
                }
            } else {
                val speaker = settingsManager.sonosSpeaker.first()
                if (speaker.isNotBlank()) {
                    Python.getInstance().getModule("sonos_bridge").callAttr("seek", positionSeconds, speaker)
                }
            }
        }
    }

    fun setSpeakerVolume(speakerName: String, volume: Float) {
        scope.launch {
            val py = Python.getInstance()
            py.getModule("sonos_bridge").callAttr("set_volume", volume.toInt(), speakerName)
            _trackState.update { state ->
                val updatedMembers = state.groupMembers.map { 
                    if (it.name == speakerName) it.copy(volume = volume) else it
                }
                state.copy(groupMembers = updatedMembers)
            }
        }
    }

    fun setSonosVolume(volume: Float) {
        scope.launch {
            val speaker = settingsManager.sonosSpeaker.first()
            if (speaker.isNotBlank()) {
                if (_trackState.value.isGrouped) {
                    // Whole group - Sonos scales each room, keeping their
                    // levels relative to each other. Rooms are re-read on
                    // the next poll.
                    val ok = Python.getInstance().getModule("sonos_bridge")
                        .callAttr("set_group_volume", volume.toInt(), speaker).toBoolean()
                    if (ok) _currentVolume.value = volume
                } else {
                    setSpeakerVolume(speaker, volume)
                }
                // A device's mute flag is independent of its volume level in
                // Sonos/SoCo - changing volume alone doesn't clear it, so the
                // speaker would stay silent even as the slider/label move.
                // Adjusting volume is an explicit "I want sound" action, so
                // unmute alongside it.
                if (_trackState.value.isMuted) {
                    setMute(speaker, false)
                }
            }
        }
    }

    private fun reportError(message: String) {
        _recentErrors.update { errors ->
            val error = synchronized(this) {
                CommandError(nextErrorId++, message, System.currentTimeMillis())
            }
            (listOf(error) + errors).take(MAX_RECENT_ERRORS)
        }
    }

    // askIfUnplayable: a user's tap rather than e.g. Continue With a Random
    // Album - if a favorite that's no longer on Sonos won't play, the hosts
    // then ask whether to delete it (see unplayableFavorite).
    fun playAlbum(album: AlbumHistoryItem, askIfUnplayable: Boolean = false) {
        lastStartedFavorite = album to System.currentTimeMillis()
        scope.launch {
            val py = Python.getInstance()
            val speaker = settingsManager.sonosSpeaker.first()
            _trackState.update { it.copy(lastCommandSent = "Attempting: '${album.albumTitle}'...") }
            fun playStored(): String = py.getModule("sonos_bridge")
                .callAttr("play_stored_favorite", album.replay, speaker).toString()
            var fromSavedData = album.isSavedLocally()
            val result = try {
                if (fromSavedData) playStored() else {
                    val live = py.getModule("sonos_bridge").callAttr(
                        "play_album_smart",
                        album.albumTitle,
                        album.artist,
                        album.playUri,
                        album.playMetadata,
                        speaker
                    ).toString()
                    // Removed from Sonos since the last refresh - play what
                    // was saved for it, and refresh so it's marked as gone.
                    if (live == "FAIL: Not a valid Sonos Favorite" && album.replay.isNotBlank()) {
                        fromSavedData = true
                        scope.launch { reloadFavorites() }
                        playStored()
                    } else live
                }
            } catch (e: Exception) {
                "CRITICAL ERR: ${e.message}"
            }
            val status = "$result: '${album.albumTitle}'"
            _trackState.update { it.copy(lastCommandSent = status) }
            val failed = result.contains("FAIL") || result.contains("ERR")
            if (failed) reportError(status)
            // An offline speaker says nothing about whether the favorite
            // itself still works.
            if (failed && fromSavedData && askIfUnplayable && result != "ERR: Speaker Offline") {
                _unplayableFavorite.value = album
            }
        }
    }

    fun dismissUnplayableFavorite() {
        _unplayableFavorite.value = null
    }

    // Removes a favorite from the app only - nothing on Sonos changes. For
    // one still on Sonos the next refresh would bring it back, so the UI
    // only offers this for favorites no longer there.
    fun deleteSavedFavorite(item: AlbumHistoryItem) {
        val key = favoriteKey(item)
        scope.launch {
            savedLock.withLock {
                ensureSavedLoaded()
                updateSaved(_savedFavorites.value.filterNot { favoriteKey(it) == key })
            }
            publishFavorites()
        }
    }

    // Cover Flow's X button and the grid's long press: gone everywhere. A
    // favorite still on Sonos is deleted from Sonos too (the UI confirms
    // first); one that's only saved here is just removed.
    fun deleteFavoriteEverywhere(item: AlbumHistoryItem) {
        if (item.onSonos) deleteSonosFavorite(item, keepInApp = false) else deleteSavedFavorite(item)
    }

    // PERMANENTLY deletes the favorite from the user's Sonos system - the
    // UI must confirm before calling it. keepInApp keeps it here as a
    // local-only favorite (freeing up one of Sonos's ~70 slots); otherwise
    // it's removed from the app too. The change shows optimistically; on
    // failure the list is reloaded from the speaker, and the reason is
    // shown as a Toast.
    fun deleteSonosFavorite(item: AlbumHistoryItem, keepInApp: Boolean = false) {
        val key = favoriteKey(item)
        fun applyLocally() {
            val updated = _savedFavorites.value.mapNotNull {
                when {
                    favoriteKey(it) != key -> it
                    keepInApp -> it.asLocalOnly()
                    else -> null
                }
            }
            updateSaved(updated)
        }
        _albumHistory.value = if (keepInApp) {
            _albumHistory.value.map { if (favoriteKey(it) == key) it.asLocalOnly() else it }
        } else {
            _albumHistory.value.filterNot { favoriteKey(it) == key }
        }
        scope.launch {
            val speaker = settingsManager.sonosSpeaker.first()
            val result = try {
                withTimeoutOrNull(8000) {
                    Python.getInstance().getModule("sonos_bridge")
                        .callAttr("delete_sonos_favorite", item.playUri, item.albumTitle, speaker)
                        .toString()
                } ?: "FAIL: Speaker timed out"
            } catch (e: Exception) {
                "FAIL: ${e.message}"
            }

            val message = if (result == "OK") {
                savedLock.withLock {
                    ensureSavedLoaded()
                    applyLocally()
                }
                publishFavorites()
                if (keepInApp) "Removed \"${item.albumTitle}\" from Sonos Favorites - still saved in the app"
                else "Deleted \"${item.albumTitle}\" from Sonos Favorites"
            } else {
                reloadFavorites()
                "Couldn't delete: ${result.removePrefix("FAIL: ")}".also { reportError("$it: '${item.albumTitle}'") }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // Experimental (Settings > Home Assistant > Auto-Unmute After Commercials).
    // While a show plays, its time remaining only ticks down (~2s per poll),
    // but coming back from a commercial break the TV reports the show's
    // remaining time again - noticeably MORE than the ad had left. Treat that
    // upward jump as "the ad is over" and unmute. Written from OkHttp's
    // callback thread, hence @Volatile.
    @Volatile
    private var lastTvRemainingSeconds: Int? = null

    private fun maybeAutoUnmuteAfterAds(haTrack: TrackState, speaker: String, muted: Boolean, enabled: Boolean) {
        if (haTrack.durationSeconds <= 0 || !haTrack.isPlaying) {
            // Failed fetch, paused, or no timing info - start fresh next time
            // rather than comparing across the gap.
            lastTvRemainingSeconds = null
            return
        }
        val remaining = haTrack.durationSeconds - haTrack.positionSeconds
        val previous = lastTvRemainingSeconds
        lastTvRemainingSeconds = remaining
        if (enabled && muted && previous != null && remaining - previous > AD_END_REMAINING_JUMP_SECONDS) {
            Log.i("SonosDashboard", "Auto-unmute: time remaining jumped ${previous}s -> ${remaining}s")
            setMute(speaker, false)
        }
    }

    // Settings > General > Continue With a Random Album. Mid-queue track
    // changes stay PLAYING (maybe TRANSITIONING) - only running out of queue
    // goes to STOPPED. So "STOPPED right when the final track was due to
    // end" means the album played to its end, as opposed to a pause
    // (PAUSED_PLAYBACK) or a stop pressed mid-album. The end time is
    // predicted from the last PLAYING refresh, since with speaker events
    // those can be many seconds apart. In shuffle the queue position isn't
    // the play order, so any track can be the final one. Radio, TV etc.
    // never play from the queue, so never arm this.
    private var finalTrackEndsAtMs: Long? = null

    private fun maybeContinueWithRandomAlbum(
        state: String,
        fromQueue: Boolean,
        shuffled: Boolean,
        onLastTrack: Boolean,
        positionSeconds: Int,
        durationSeconds: Int,
        enabled: Boolean
    ) {
        val now = System.currentTimeMillis()
        when (state) {
            "PLAYING" -> finalTrackEndsAtMs =
                if (fromQueue && (onLastTrack || shuffled) && durationSeconds > 0) {
                    now + (durationSeconds - positionSeconds) * 1000L
                } else null
            "TRANSITIONING" -> Unit
            "STOPPED" -> {
                val endsAt = finalTrackEndsAtMs
                if (enabled && endsAt != null && now >= endsAt - ALBUM_END_WINDOW_SECONDS * 1000L) {
                    val current = _trackState.value.album
                    val candidates = _favoriteAlbumsRef.value.filter {
                        it.playUri.isNotBlank() && !it.albumTitle.equals(current, ignoreCase = true)
                    }
                    candidates.randomOrNull()?.let { album ->
                        Log.i("SonosDashboard", "Album finished - continuing with '${album.albumTitle}'")
                        playAlbum(album)
                    }
                }
                finalTrackEndsAtMs = null
            }
            else -> finalTrackEndsAtMs = null
        }
    }

    fun toggleMute() {
        scope.launch {
            val speaker = settingsManager.sonosSpeaker.first()
            if (speaker.isNotBlank()) {
                setMute(speaker, !_trackState.value.isMuted)
            }
        }
    }

    // Uses the real Sonos mute flag (device.mute) rather than zeroing/
    // restoring volume, so the actual volume level is left untouched and
    // stays correct even if the speaker gets muted/unmuted from elsewhere
    // (Sonos app, Alexa, a physical button).
    // Mutes the whole group when rooms are grouped (matching the group
    // volume slider), otherwise just this speaker.
    private fun setMute(speakerName: String, muted: Boolean) {
        scope.launch {
            val py = Python.getInstance()
            val function = if (_trackState.value.isGrouped) "set_group_mute" else "set_mute"
            val ok = py.getModule("sonos_bridge").callAttr(function, muted, speakerName).toBoolean()
            if (ok) {
                _trackState.update { it.copy(isMuted = muted) }
            }
        }
    }
}
