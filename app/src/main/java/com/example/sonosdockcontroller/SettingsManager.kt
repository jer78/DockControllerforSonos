package com.example.sonosdockcontroller

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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

        // Sonos never reports a real artist for album favorites (only
        // title/resource/class), so this caches artist names learned by
        // observing a favorited album actually play, keyed by the
        // favorite's playUri - backfilled into the Favorites display.
        val FAVORITE_ARTIST_CACHE_JSON = stringPreferencesKey("favorite_artist_cache_json")
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
