package com.example.sonosdockcontroller

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

val Context.dataStore by preferencesDataStore(name = "dock_settings")

class SettingsManager(private val context: Context) {
    companion object {
        val SELECTED_SONOS_SPEAKER = stringPreferencesKey("sonos_speaker")
        val HA_SERVER_URL = stringPreferencesKey("ha_url")
        val HA_ACCESS_TOKEN = stringPreferencesKey("ha_token")
        val SELECTED_LIGHT_ENTITY = stringPreferencesKey("ha_light_entity")
        val SELECTED_CLIMATE_ENTITY = stringPreferencesKey("ha_climate_entity")
        val SELECTED_HA_MEDIA_PLAYER = stringPreferencesKey("ha_media_player")
        val COVER_FLOW_MODE = stringPreferencesKey("cover_flow_mode") // "history" or "favorites"
        
        // Favorite Filters
        val FILTER_ALBUMS = booleanPreferencesKey("filter_albums")
        val FILTER_PLAYLISTS = booleanPreferencesKey("filter_playlists")
        val FILTER_RADIO = booleanPreferencesKey("filter_radio")

        val ALBUM_HISTORY_JSON = stringPreferencesKey("album_history_json")

        // Mic-based spectrum analyzer shown behind Now Playing (see SpectrumAnalyzer.kt)
        val SHOW_SPECTRUM_ANALYZER = booleanPreferencesKey("show_spectrum_analyzer")
        // Stores a SpectrumColorScheme's name (see DashboardUI.kt)
        val SPECTRUM_COLOR_SCHEME = stringPreferencesKey("spectrum_color_scheme")
        // Fraction (0-1) of the screen height, measured from the bottom, the
        // visualizer occupies when it's not confined to the album art.
        val SPECTRUM_HEIGHT_PERCENT = floatPreferencesKey("spectrum_height_percent")
        // When true, bars are drawn only over the Now Playing album art
        // square instead of spanning the full screen.
        val SPECTRUM_CONFINE_TO_ALBUM_ART = booleanPreferencesKey("spectrum_confine_to_album_art")

        // Cover Flow's ambient clock: true = 24-hour ("14:30"), false = 12-hour ("2:30 PM").
        val USE_24_HOUR_CLOCK = booleanPreferencesKey("use_24_hour_clock")

        // How long TV mode sits on the Now Playing screen (actively playing)
        // before dropping into the TV Ambient screensaver, in seconds.
        val TV_SCREENSAVER_DELAY_SECONDS = intPreferencesKey("tv_screensaver_delay_seconds")

        // Sonos never reports a real artist for album favorites (only
        // title/resource/class), so this caches artist names learned by
        // observing a favorited album actually play, keyed by the
        // favorite's playUri - backfilled into the Favorites display.
        val FAVORITE_ARTIST_CACHE_JSON = stringPreferencesKey("favorite_artist_cache_json")

        const val TV_SCREENSAVER_DELAY_MIN_SECONDS = 30
        const val TV_SCREENSAVER_DELAY_MAX_SECONDS = 1800
        const val TV_SCREENSAVER_DELAY_DEFAULT_SECONDS = 30
    }

    val sonosSpeaker: Flow<String> = context.dataStore.data.map { it[SELECTED_SONOS_SPEAKER] ?: "" }
    val haUrl: Flow<String> = context.dataStore.data.map { it[HA_SERVER_URL] ?: "" }
    val haToken: Flow<String> = context.dataStore.data.map { it[HA_ACCESS_TOKEN] ?: "" }
    val selectedLight: Flow<String> = context.dataStore.data.map { it[SELECTED_LIGHT_ENTITY] ?: "" }
    val selectedClimate: Flow<String> = context.dataStore.data.map { it[SELECTED_CLIMATE_ENTITY] ?: "" }
    val selectedHaMediaPlayer: Flow<String> = context.dataStore.data.map { it[SELECTED_HA_MEDIA_PLAYER] ?: "" }
    val coverFlowMode: Flow<String> = context.dataStore.data.map { it[COVER_FLOW_MODE] ?: "history" }
    
    val filterAlbums: Flow<Boolean> = context.dataStore.data.map { it[FILTER_ALBUMS] ?: true }
    val filterPlaylists: Flow<Boolean> = context.dataStore.data.map { it[FILTER_PLAYLISTS] ?: true }
    val filterRadio: Flow<Boolean> = context.dataStore.data.map { it[FILTER_RADIO] ?: true }

    val showSpectrumAnalyzer: Flow<Boolean> = context.dataStore.data.map { it[SHOW_SPECTRUM_ANALYZER] ?: false }

    suspend fun setShowSpectrumAnalyzer(enabled: Boolean) {
        context.dataStore.edit { it[SHOW_SPECTRUM_ANALYZER] = enabled }
    }

    // Stored as the enum's name; falls back to RAINBOW if unset or stale
    // (e.g. a scheme that no longer exists after an update).
    val spectrumColorScheme: Flow<SpectrumColorScheme> = context.dataStore.data.map { prefs ->
        val name = prefs[SPECTRUM_COLOR_SCHEME]
        SpectrumColorScheme.entries.find { it.name == name } ?: SpectrumColorScheme.RAINBOW
    }

    suspend fun setSpectrumColorScheme(scheme: SpectrumColorScheme) {
        context.dataStore.edit { it[SPECTRUM_COLOR_SCHEME] = scheme.name }
    }

    val spectrumHeightPercent: Flow<Float> = context.dataStore.data.map { it[SPECTRUM_HEIGHT_PERCENT] ?: 1f }

    suspend fun setSpectrumHeightPercent(percent: Float) {
        context.dataStore.edit { it[SPECTRUM_HEIGHT_PERCENT] = percent }
    }

    val spectrumConfineToAlbumArt: Flow<Boolean> =
        context.dataStore.data.map { it[SPECTRUM_CONFINE_TO_ALBUM_ART] ?: false }

    suspend fun setSpectrumConfineToAlbumArt(enabled: Boolean) {
        context.dataStore.edit { it[SPECTRUM_CONFINE_TO_ALBUM_ART] = enabled }
    }

    val use24HourClock: Flow<Boolean> = context.dataStore.data.map { it[USE_24_HOUR_CLOCK] ?: true }

    suspend fun setUse24HourClock(enabled: Boolean) {
        context.dataStore.edit { it[USE_24_HOUR_CLOCK] = enabled }
    }

    val tvScreensaverDelaySeconds: Flow<Int> = context.dataStore.data.map {
        it[TV_SCREENSAVER_DELAY_SECONDS] ?: TV_SCREENSAVER_DELAY_DEFAULT_SECONDS
    }

    suspend fun setTvScreensaverDelaySeconds(seconds: Int) {
        context.dataStore.edit {
            it[TV_SCREENSAVER_DELAY_SECONDS] =
                seconds.coerceIn(TV_SCREENSAVER_DELAY_MIN_SECONDS, TV_SCREENSAVER_DELAY_MAX_SECONDS)
        }
    }

    val albumHistory: Flow<List<AlbumHistoryItem>> = context.dataStore.data.map { prefs ->
        val json = prefs[ALBUM_HISTORY_JSON] ?: "[]"
        val list = mutableListOf<AlbumHistoryItem>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(AlbumHistoryItem(
                    artUrl = obj.getString("art"),
                    artist = obj.getString("artist"),
                    albumTitle = obj.getString("title"),
                    playUri = obj.optString("uri", ""),
                    playMetadata = obj.optString("meta", "")
                ))
            }
        } catch (e: Exception) { e.printStackTrace() }
        list
    }

    suspend fun saveSettings(
        speaker: String,
        url: String,
        token: String,
        light: String,
        climate: String,
        haMediaPlayer: String,
        mode: String,
        fAlbums: Boolean = true,
        fPlaylists: Boolean = true,
        fRadio: Boolean = true
    ) {
        context.dataStore.edit { prefs ->
            prefs[SELECTED_SONOS_SPEAKER] = speaker
            prefs[HA_SERVER_URL] = url
            prefs[HA_ACCESS_TOKEN] = token
            prefs[SELECTED_LIGHT_ENTITY] = light
            prefs[SELECTED_CLIMATE_ENTITY] = climate
            prefs[SELECTED_HA_MEDIA_PLAYER] = haMediaPlayer
            prefs[COVER_FLOW_MODE] = mode
            prefs[FILTER_ALBUMS] = fAlbums
            prefs[FILTER_PLAYLISTS] = fPlaylists
            prefs[FILTER_RADIO] = fRadio
        }
    }

    val favoriteArtistCache: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        val json = prefs[FAVORITE_ARTIST_CACHE_JSON] ?: "{}"
        val map = mutableMapOf<String, String>()
        try {
            val obj = JSONObject(json)
            obj.keys().forEach { key -> map[key] = obj.getString(key) }
        } catch (e: Exception) { e.printStackTrace() }
        map
    }

    suspend fun saveFavoriteArtistCache(cache: Map<String, String>) {
        context.dataStore.edit { prefs ->
            val obj = JSONObject()
            cache.forEach { (k, v) -> obj.put(k, v) }
            prefs[FAVORITE_ARTIST_CACHE_JSON] = obj.toString()
        }
    }

    suspend fun saveAlbumHistory(history: List<AlbumHistoryItem>) {
        context.dataStore.edit { prefs ->
            val array = JSONArray()
            history.forEach { item ->
                val obj = JSONObject()
                    .put("art", item.artUrl)
                    .put("artist", item.artist)
                    .put("title", item.albumTitle)
                    .put("uri", item.playUri)
                    .put("meta", item.playMetadata)
                array.put(obj)
            }
            prefs[ALBUM_HISTORY_JSON] = array.toString()
        }
    }
}
