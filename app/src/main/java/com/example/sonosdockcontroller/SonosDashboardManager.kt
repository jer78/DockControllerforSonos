package com.example.sonosdockcontroller

import android.content.Context
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

    private val _haState = MutableStateFlow(HAState())
    val haState: StateFlow<HAState> = _haState.asStateFlow()

    private val _isTvMode = MutableStateFlow(false)
    val isTvMode: StateFlow<Boolean> = _isTvMode.asStateFlow()

    private val _currentVolume = MutableStateFlow(30f)
    val currentVolume: StateFlow<Float> = _currentVolume.asStateFlow()

    private val _albumHistory = MutableStateFlow<List<AlbumHistoryItem>>(emptyList())
    val albumHistory: StateFlow<List<AlbumHistoryItem>> = _albumHistory.asStateFlow()

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

    private var pollingJob: Job? = null

    companion object {
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
            val history = settingsManager.albumHistory.first()
                .sortedWith(compareBy({ it.artist.lowercase() }, { it.albumTitle.lowercase() }))
            _albumHistory.value = history
        } else {
            val fAlbums = settingsManager.filterAlbums.first()
            val fPlaylists = settingsManager.filterPlaylists.first()
            val fRadio = settingsManager.filterRadio.first()
            refreshFavorites(fAlbums, fPlaylists, fRadio)
        }
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

    fun startPolling() {
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
                val pyFavs = withTimeoutOrNull(8000) {
                    pyModule.callAttr("get_sonos_favorites", speaker, filterString).asList()
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
                        val artist = _favoriteArtistCache.value[title.lowercase()] ?: rawArtist
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
                val pyFavs = withTimeoutOrNull(8000) {
                    pyModule.callAttr("get_sonos_favorites", speaker, "albums").asList()
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
                            
                            if (haMedia.isNotBlank()) {
                                haManager.fetchHAMediaPlayer(url, token, haMedia) { haTrack ->
                                    _trackState.update { it.copy(
                                        title = haTrack.title,
                                        artist = haTrack.artist,
                                        album = haTrack.album,
                                        albumArtUrl = haTrack.albumArtUrl,
                                        isPlaying = haTrack.isPlaying,
                                        prevTrack = "None",
                                        nextTrack = "None",
                                        groupMembers = memberVolumes
                                    )}
                                }
                            } else {
                                _trackState.update { it.copy(
                                    title = "TV Mode",
                                    artist = "Select HA Entity",
                                    albumArtUrl = "",
                                    isPlaying = true,
                                    groupMembers = memberVolumes
                                )}
                            }
                        } else {
                            val title = pyDict[PyObject.fromJava("title")]?.toString() ?: ""
                            val artist = pyDict[PyObject.fromJava("artist")]?.toString() ?: ""
                            val album = pyDict[PyObject.fromJava("album")]?.toString() ?: ""
                            val art = pyDict[PyObject.fromJava("album_art")]?.toString() ?: ""
                            val state = pyDict[PyObject.fromJava("state")]?.toString() ?: "STOPPED"

                            _trackState.update { it.copy(
                                title = title,
                                artist = artist,
                                album = album,
                                albumArtUrl = art,
                                isPlaying = state == "PLAYING",
                                groupMembers = memberVolumes
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
                                val match = _favoriteAlbumsRef.value.find {
                                    it.albumTitle.equals(album, ignoreCase = true)
                                }
                                if (match != null) {
                                    // Keyed by album title, not playUri: Sonos
                                    // favorite IDs reached via the category-
                                    // folder sub-browse aren't guaranteed
                                    // stable across refreshes, so a playUri-
                                    // keyed cache could "forget" an already-
                                    // learned artist the next time the same
                                    // album resolves to a different ID.
                                    val cacheKey = match.albumTitle.lowercase()

                                    // Learned regardless of mode, so Favorites
                                    // (Mode B) can backfill its own display
                                    // with the real name instead of the
                                    // generic placeholder.
                                    if (_favoriteArtistCache.value[cacheKey] != artist) {
                                        val updatedCache = _favoriteArtistCache.value + (cacheKey to artist)
                                        _favoriteArtistCache.value = updatedCache
                                        settingsManager.saveFavoriteArtistCache(updatedCache)
                                    }

                                    // Dedupe by title, not playUri, for the
                                    // same reason - otherwise the same album
                                    // can end up added 2-3x under different
                                    // (sometimes now-stale) IDs, showing as
                                    // duplicate cards in Cover Flow where only
                                    // the most recently captured one actually
                                    // still resolves to a playable favorite.
                                    if (currentMode == "history" && _albumHistory.value.none { it.albumTitle.equals(match.albumTitle, ignoreCase = true) }) {
                                        val newItem = match.copy(artist = artist)
                                        val currentHistory = (_albumHistory.value + newItem)
                                            .sortedWith(compareBy({ it.artist.lowercase() }, { it.albumTitle.lowercase() }))
                                            .take(100)
                                        _albumHistory.value = currentHistory
                                        settingsManager.saveAlbumHistory(currentHistory)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            delay(2000)
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
            delay(5000)
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
            val py = Python.getInstance()
            val speaker = settingsManager.sonosSpeaker.first()
            if (speaker.isNotBlank()) {
                _trackState.update { it.copy(lastCommandSent = "Action: $action") }
                py.getModule("sonos_bridge").callAttr("control_playback", action, speaker)
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

    fun playAlbum(album: AlbumHistoryItem) {
        scope.launch {
            val py = Python.getInstance()
            val speaker = settingsManager.sonosSpeaker.first()
            _trackState.update { it.copy(lastCommandSent = "Attempting: '${album.albumTitle}'...") }
            val result = py.getModule("sonos_bridge").callAttr(
                "play_album_smart", 
                album.albumTitle, 
                album.artist, 
                album.playUri, 
                album.playMetadata, 
                speaker
            ).toString()
            _trackState.update { it.copy(lastCommandSent = "$result: '${album.albumTitle}'") }
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
            val currentList = _albumHistory.value.toMutableList()
            val removed = currentList.removeAll { it.artUrl == artUrl }
            if (removed) {
                _albumHistory.value = currentList
                if (_coverFlowMode.value == "history") {
                    settingsManager.saveAlbumHistory(currentList)
                }
            }
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
