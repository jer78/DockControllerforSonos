package io.github.jer78.dockcontroller

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

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

    // Reference list of favorited ALBUMS, used only for matching what's
    // currently playing against - not shown directly in the UI. Mode A
    // (History) only tracks plays of albums that are already Sonos
    // Favorites, since that's the only reliable, resolvable way to replay
    // them later (a captured/replayed raw URI gets rejected by Sonos for
    // cloud-service content, even with correct metadata).
    private val _favoriteAlbumsRef = MutableStateFlow<List<AlbumHistoryItem>>(emptyList())

    // Artist names learned by observing a favorited album actually play
    // (Sonos never reports a real artist for album favorites), keyed by
    // playUri. Backfilled into the Favorites (Mode B) display.
    private val _favoriteArtistCache = MutableStateFlow<Map<String, String>>(emptyMap())

    private val _discoveredSpeakers = MutableStateFlow<List<String>>(emptyList())
    val discoveredSpeakers: StateFlow<List<String>> = _discoveredSpeakers.asStateFlow()

    private val _speakerGroups = MutableStateFlow<List<SonosSpeakerGroup>>(emptyList())
    val speakerGroups: StateFlow<List<SonosSpeakerGroup>> = _speakerGroups.asStateFlow()

    private val _coverFlowMode = MutableStateFlow("history")
    val coverFlowMode: StateFlow<String> = _coverFlowMode.asStateFlow()

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
        // The bridge pages through every favorite (100 per round trip to
        // the speaker), so large collections need longer than a single call.
        private const val FAVORITES_FETCH_TIMEOUT_MS = 20000L
        private const val MAX_RECENT_ERRORS = 5
        // How far time remaining must jump UP between TV polls to count as
        // coming back from a commercial - well clear of poll/clock jitter,
        // well under the minutes a show and an ad typically differ by.
        private const val AD_END_REMAINING_JUMP_SECONDS = 10
        @Volatile
        private var instance: SonosDashboardManager? = null

        fun getInstance(context: Context): SonosDashboardManager {
            return instance ?: synchronized(this) {
                instance ?: SonosDashboardManager(context).also { instance = it }
            }
        }
    }

    // Shared by startPolling, pollSonos's mode-switch detection, and the
    // immediate post-Save refresh - reloads whichever data source (History
    // or Favorites) is correct for the given mode.
    private suspend fun reloadAlbumSourceForMode(mode: String) {
        if (mode == "history") {
            showHistory(settingsManager.albumHistory.first())
        } else {
            val fAlbums = settingsManager.filterAlbums.first()
            val fPlaylists = settingsManager.filterPlaylists.first()
            val fRadio = settingsManager.filterRadio.first()
            refreshFavorites(fAlbums, fPlaylists, fRadio)
        }
    }

    // History is stored in the order albums were first played, oldest
    // first, and never trimmed by the Cover Flow limit - so lowering the
    // limit only hides the oldest albums, and raising it brings them back.
    // Cover Flow shows the newest `limit` of them, sorted by artist/album.
    private suspend fun showHistory(stored: List<AlbumHistoryItem>) {
        val limit = settingsManager.coverFlowLimit.first()
        val newest = if (limit == SettingsManager.COVER_FLOW_LIMIT_UNLIMITED) stored else stored.takeLast(limit)
        _albumHistory.value = newest
            .sortedWith(compareBy({ it.artist.lowercase() }, { it.albumTitle.lowercase() }))
    }

    // Called right after Settings saves, so a reopened Settings dialog shows
    // the correct "Clear History (N)" count immediately - without this,
    // stale data lingers until pollSonos's next 2s cycle catches the mode
    // change on its own.
    fun refreshAfterSettingsSave() {
        scope.launch {
            val mode = settingsManager.coverFlowMode.first()
            _coverFlowMode.value = mode
            reloadAlbumSourceForMode(mode)
        }
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
            _coverFlowMode.value = settingsManager.coverFlowMode.first()
            _favoriteArtistCache.value = settingsManager.favoriteArtistCache.first()
            // Needed regardless of mode: History matches plays against it,
            // and Favorites' own artist-learning (see pollSonos) needs it too.
            refreshFavoriteAlbumsRef()

            reloadAlbumSourceForMode(_coverFlowMode.value)
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

    fun refreshFavorites(fAlbums: Boolean, fPlaylists: Boolean, fRadio: Boolean) {
        scope.launch {
            _favoritesRefreshStatus.value = "Starting Instant Scan..."
            _favoritesRefreshCount.value = null
            val py = Python.getInstance()
            val pyModule = py.getModule("sonos_bridge")
            val speaker = settingsManager.sonosSpeaker.first()
            
            if (speaker.isBlank()) {
                _favoritesRefreshStatus.value = "Error: Select a speaker first"
                return@launch
            }

            val filters = mutableListOf<String>()
            if (fAlbums) filters.add("albums")
            if (fPlaylists) filters.add("playlists")
            if (fRadio) filters.add("radio")
            val filterString = if (filters.isEmpty()) "none" else filters.joinToString(",")

            try {
                val limit = settingsManager.coverFlowLimit.first()
                val pyFavs = withTimeoutOrNull(FAVORITES_FETCH_TIMEOUT_MS) {
                    pyModule.callAttr("get_sonos_favorites", speaker, filterString, limit).asList()
                }

                if (pyFavs == null) {
                    _favoritesRefreshStatus.value = "Error: Speaker timed out"
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
                        val uri = map[PyObject.fromJava("uri")]?.toString() ?: ""
                        val rawArtist = map[PyObject.fromJava("artist")]?.toString() ?: "Unknown Artist"
                        // Sonos doesn't report a real artist for album
                        // favorites - use the name we've learned from
                        // actually seeing it play, if we have one. Keyed by
                        // title, not this fetch's uri, since that ID isn't
                        // guaranteed stable across refreshes.
                        val artist = learnedArtistFor(title) ?: rawArtist
                        favItems.add(AlbumHistoryItem(
                            artUrl = map[PyObject.fromJava("art")]?.toString() ?: "",
                            artist = artist,
                            albumTitle = title,
                            playUri = uri,
                            playMetadata = map[PyObject.fromJava("meta")]?.toString() ?: ""
                        ))
                    }
                }
                
                _albumHistory.value = favItems
                _favoritesRefreshCount.value = favItems.size
                if (favItems.isNotEmpty()) {
                    _favoritesRefreshStatus.value = "Success: Found ${favItems.size} items"
                } else if (diagMsg.isNotBlank()) {
                    _favoritesRefreshStatus.value = "0 items | $diagMsg"
                } else {
                    _favoritesRefreshStatus.value = "Success: 0 items found"
                }
            } catch (e: Exception) {
                _favoritesRefreshStatus.value = "Critical Error: ${e.message}"
            }
        }
    }

    private fun refreshFavoriteAlbumsRef() {
        scope.launch {
            val py = Python.getInstance()
            val pyModule = py.getModule("sonos_bridge")
            val speaker = settingsManager.sonosSpeaker.first()
            if (speaker.isBlank()) return@launch

            try {
                // Always the complete list, whatever the Cover Flow limit -
                // History can only capture albums found in here.
                val pyFavs = withTimeoutOrNull(FAVORITES_FETCH_TIMEOUT_MS) {
                    pyModule.callAttr("get_sonos_favorites", speaker, "albums", SettingsManager.COVER_FLOW_LIMIT_UNLIMITED).asList()
                } ?: return@launch

                val items = mutableListOf<AlbumHistoryItem>()
                for (item in pyFavs) {
                    val map = item.asMap()
                    val title = map[PyObject.fromJava("title")]?.toString() ?: ""
                    if (title == "DIAGNOSTIC" || title == "DIAG") continue
                    items.add(AlbumHistoryItem(
                        artUrl = map[PyObject.fromJava("art")]?.toString() ?: "",
                        artist = map[PyObject.fromJava("artist")]?.toString() ?: "Sonos Favorite",
                        albumTitle = title,
                        playUri = map[PyObject.fromJava("uri")]?.toString() ?: "",
                        playMetadata = map[PyObject.fromJava("meta")]?.toString() ?: ""
                    ))
                }
                _favoriteAlbumsRef.value = items
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
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

    // Older cache entries were keyed by the full lowercased title (brackets
    // and all), so check that form too rather than losing those artists.
    private fun learnedArtistFor(title: String): String? {
        val cache = _favoriteArtistCache.value
        return cache[normalizeAlbumTitle(title)] ?: cache[title.lowercase()]
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

        var favoritePollCounter = 0
        var lastKnownMode: String? = null

        while (true) {
            try {
                val speaker = settingsManager.sonosSpeaker.first()
                if (speaker.isNotBlank()) {
                    val currentMode = settingsManager.coverFlowMode.first()
                    _coverFlowMode.value = currentMode

                    if (lastKnownMode != null && currentMode != lastKnownMode) {
                        // Mode was switched via Settings while already running.
                        // _albumHistory otherwise keeps showing whatever the
                        // PREVIOUS mode last populated it with (e.g. every
                        // Favorite, if coming from Mode B) - reload the right
                        // data for the new mode instead. This matters a lot
                        // for History: without this, a later match would
                        // append to - and persist - that contaminated list,
                        // polluting real history with never-played favorites.
                        reloadAlbumSourceForMode(currentMode)
                    }
                    lastKnownMode = currentMode

                    if (favoritePollCounter >= 1800) {
                        // Once an hour (1800 cycles * 2s) - needed regardless
                        // of mode: History matches plays against it, and
                        // Favorites' artist-learning below needs it too.
                        refreshFavoriteAlbumsRef()
                        if (currentMode == "favorites") {
                            val fAlbums = settingsManager.filterAlbums.first()
                            val fPlaylists = settingsManager.filterPlaylists.first()
                            val fRadio = settingsManager.filterRadio.first()
                            refreshFavorites(fAlbums, fPlaylists, fRadio)
                        }
                        favoritePollCounter = 0
                    }
                    favoritePollCounter++

                    if (favoritePollCounter % 5 == 0) {
                        refreshSpeakerGroups()
                        refreshAudioSettings()
                    }

                    val pyDict = pyModule.callAttr("get_now_playing", speaker).asMap()

                    if (!pyDict.containsKey(PyObject.fromJava("error"))) {
                        val tvDetected = pyDict[PyObject.fromJava("is_tv")]?.toBoolean() ?: false
                        val vol = pyDict[PyObject.fromJava("volume")]?.toString()?.toFloatOrNull() ?: 30f
                        val muted = pyDict[PyObject.fromJava("mute")]?.toBoolean() ?: false

                        _isTvMode.value = tvDetected
                        _currentVolume.value = vol
                        _trackState.update { it.copy(isMuted = muted) }

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

                            if (haMedia.isNotBlank()) {
                                haManager.fetchHAMediaPlayer(url, token, haMedia) { haTrack ->
                                    maybeAutoUnmuteAfterAds(haTrack, speaker, muted, autoUnmute)
                                    _trackState.update { it.copy(
                                        title = haTrack.title,
                                        artist = haTrack.artist,
                                        album = haTrack.album,
                                        albumArtUrl = haTrack.albumArtUrl,
                                        isPlaying = haTrack.isPlaying,
                                        prevTrack = "None",
                                        nextTrack = "None",
                                        groupMembers = memberVolumes,
                                        positionSeconds = haTrack.positionSeconds,
                                        durationSeconds = haTrack.durationSeconds
                                    )}
                                }
                            } else {
                                _trackState.update { it.copy(
                                    title = "TV Mode",
                                    artist = "Select HA Entity",
                                    albumArtUrl = "",
                                    isPlaying = true,
                                    groupMembers = memberVolumes,
                                    positionSeconds = 0,
                                    durationSeconds = 0
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
                                isPlaying = state == "PLAYING",
                                groupMembers = memberVolumes,
                                positionSeconds = position,
                                durationSeconds = duration,
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
                                val normalizedAlbum = normalizeAlbumTitle(album)
                                val match = _favoriteAlbumsRef.value.find {
                                    it.albumTitle.equals(album, ignoreCase = true)
                                } ?: _favoriteAlbumsRef.value.find {
                                    normalizeAlbumTitle(it.albumTitle) == normalizedAlbum
                                }
                                if (match != null) {
                                    // Keyed by album title, not playUri: Sonos
                                    // favorite IDs reached via the category-
                                    // folder sub-browse aren't guaranteed
                                    // stable across refreshes, so a playUri-
                                    // keyed cache could "forget" an already-
                                    // learned artist the next time the same
                                    // album resolves to a different ID.
                                    val cacheKey = normalizeAlbumTitle(match.albumTitle)

                                    // Learned regardless of mode, so Favorites
                                    // (Mode B) can backfill its own display
                                    // with the real name instead of the
                                    // generic placeholder.
                                    if (_favoriteArtistCache.value[cacheKey] != artist) {
                                        val updatedCache = _favoriteArtistCache.value + (cacheKey to artist)
                                        _favoriteArtistCache.value = updatedCache
                                        settingsManager.saveFavoriteArtistCache(updatedCache)
                                    }

                                    // Show it in Favorites straight away rather
                                    // than waiting for the next favorites refresh
                                    // to pick it up from the cache.
                                    if (currentMode == "favorites" && _albumHistory.value.any { normalizeAlbumTitle(it.albumTitle) == cacheKey && it.artist != artist }) {
                                        _albumHistory.value = _albumHistory.value.map {
                                            if (normalizeAlbumTitle(it.albumTitle) == cacheKey) it.copy(artist = artist) else it
                                        }
                                    }

                                    // Dedupe by title, not playUri, for the
                                    // same reason - otherwise the same album
                                    // can end up added 2-3x under different
                                    // (sometimes now-stale) IDs, showing as
                                    // duplicate cards in Cover Flow where only
                                    // the most recently captured one actually
                                    // still resolves to a playable favorite.
                                    if (currentMode == "history") {
                                        val stored = settingsManager.albumHistory.first()
                                        if (stored.none { it.albumTitle.equals(match.albumTitle, ignoreCase = true) }) {
                                            val updated = stored + match.copy(artist = artist)
                                            settingsManager.saveAlbumHistory(updated)
                                            showHistory(updated)
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
            delay(if (_isIdleScreen.value) IDLE_POLL_INTERVAL_MS else ACTIVE_POLL_INTERVAL_MS)
        }
    }

    // The configured unit rarely/never changes at runtime, so fetch it once
    // per url/token rather than on every 5s poll cycle.
    private var cachedTemperatureUnit = "°C"
    private var cachedTemperatureUnitFor = ""

    private suspend fun pollHA() {
        while (true) {
            val url = settingsManager.haUrl.first()
            val token = settingsManager.haToken.first()
            val light = settingsManager.selectedLight.first()
            val climate = settingsManager.selectedClimate.first()
            if (url.isNotBlank() && token.isNotBlank()) {
                val cacheKey = "$url|$token"
                if (cachedTemperatureUnitFor != cacheKey) {
                    haManager.fetchTemperatureUnit(url, token) { unit ->
                        cachedTemperatureUnit = unit
                        cachedTemperatureUnitFor = cacheKey
                    }
                }
                haManager.pollHAState(url, token, light, climate, cachedTemperatureUnit) { newState ->
                    _haState.value = newState
                }
            }
            delay(if (_isIdleScreen.value) IDLE_HA_POLL_INTERVAL_MS else ACTIVE_HA_POLL_INTERVAL_MS)
        }
    }

    fun setClimateTemperature(temp: Double? = null, tempHigh: Double? = null, tempLow: Double? = null) {
        scope.launch {
            val url = settingsManager.haUrl.first()
            val token = settingsManager.haToken.first()
            val climate = settingsManager.selectedClimate.first()
            haManager.setClimateTemperature(url, token, climate, temp, tempHigh, tempLow)
        }
    }

    fun setClimateHvacMode(hvacMode: String) {
        scope.launch {
            val url = settingsManager.haUrl.first()
            val token = settingsManager.haToken.first()
            val climate = settingsManager.selectedClimate.first()
            haManager.setClimateHvacMode(url, token, climate, hvacMode)
        }
    }

    fun setClimateFanMode(fanMode: String) {
        scope.launch {
            val url = settingsManager.haUrl.first()
            val token = settingsManager.haToken.first()
            val climate = settingsManager.selectedClimate.first()
            haManager.setClimateFanMode(url, token, climate, fanMode)
        }
    }

    fun setClimatePresetMode(presetMode: String) {
        scope.launch {
            val url = settingsManager.haUrl.first()
            val token = settingsManager.haToken.first()
            val climate = settingsManager.selectedClimate.first()
            haManager.setClimatePresetMode(url, token, climate, presetMode)
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
                setSpeakerVolume(speaker, volume)
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

    fun toggleLight() {
        scope.launch {
            val url = settingsManager.haUrl.first()
            val token = settingsManager.haToken.first()
            val light = settingsManager.selectedLight.first()
            haManager.toggleLight(url, token, light)
        }
    }

    fun setLightBrightness(brightness: Int) {
        scope.launch {
            val url = settingsManager.haUrl.first()
            val token = settingsManager.haToken.first()
            val light = settingsManager.selectedLight.first()
            haManager.setLightBrightness(url, token, light, brightness)
        }
    }

    fun setLightOn(on: Boolean) {
        scope.launch {
            val url = settingsManager.haUrl.first()
            val token = settingsManager.haToken.first()
            val light = settingsManager.selectedLight.first()
            haManager.setLightOn(url, token, light, on)
        }
    }

    fun setLightColorRgb(r: Int, g: Int, b: Int) {
        scope.launch {
            val url = settingsManager.haUrl.first()
            val token = settingsManager.haToken.first()
            val light = settingsManager.selectedLight.first()
            haManager.setLightColorRgb(url, token, light, r, g, b)
        }
    }

    fun setLightColorTempKelvin(kelvin: Int) {
        scope.launch {
            val url = settingsManager.haUrl.first()
            val token = settingsManager.haToken.first()
            val light = settingsManager.selectedLight.first()
            haManager.setLightColorTempKelvin(url, token, light, kelvin)
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

    fun playAlbum(album: AlbumHistoryItem) {
        scope.launch {
            val py = Python.getInstance()
            val speaker = settingsManager.sonosSpeaker.first()
            _trackState.update { it.copy(lastCommandSent = "Attempting: '${album.albumTitle}'...") }
            val result = try {
                py.getModule("sonos_bridge").callAttr(
                    "play_album_smart",
                    album.albumTitle,
                    album.artist,
                    album.playUri,
                    album.playMetadata,
                    speaker
                ).toString()
            } catch (e: Exception) {
                "CRITICAL ERR: ${e.message}"
            }
            val status = "$result: '${album.albumTitle}'"
            _trackState.update { it.copy(lastCommandSent = status) }
            if (result.contains("FAIL") || result.contains("ERR")) reportError(status)
        }
    }

    fun clearAlbumHistory() {
        scope.launch {
            _albumHistory.value = emptyList()
            settingsManager.saveAlbumHistory(emptyList())
        }
    }

    fun removeAlbumFromHistory(artUrl: String) {
        scope.launch {
            if (_coverFlowMode.value == "history") {
                val stored = settingsManager.albumHistory.first()
                val updated = stored.filterNot { it.artUrl == artUrl }
                if (updated.size != stored.size) {
                    settingsManager.saveAlbumHistory(updated)
                    // Lets the next-oldest album, if any was over the
                    // limit, take the removed one's place.
                    showHistory(updated)
                }
            } else {
                _albumHistory.value = _albumHistory.value.filterNot { it.artUrl == artUrl }
            }
        }
    }

    // Favorites mode's X button. Unlike removeAlbumFromHistory (which only
    // hides a card locally), this PERMANENTLY deletes the favorite from the
    // user's Sonos system - the UI must confirm before calling it. The card
    // is removed optimistically; on failure the list is reloaded from the
    // speaker so it reappears, and the reason is shown as a Toast.
    fun deleteSonosFavorite(item: AlbumHistoryItem) {
        _albumHistory.value = _albumHistory.value.filterNot { it.playUri == item.playUri }
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
                _favoriteAlbumsRef.value = _favoriteAlbumsRef.value.filterNot { it.playUri == item.playUri }
                "Deleted \"${item.albumTitle}\" from Sonos Favorites"
            } else {
                reloadAlbumSourceForMode(_coverFlowMode.value)
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
    private fun setMute(speakerName: String, muted: Boolean) {
        scope.launch {
            val py = Python.getInstance()
            val ok = py.getModule("sonos_bridge").callAttr("set_mute", muted, speakerName).toBoolean()
            if (ok) {
                _trackState.update { it.copy(isMuted = muted) }
            }
        }
    }
}
