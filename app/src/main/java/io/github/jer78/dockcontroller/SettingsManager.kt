package io.github.jer78.dockcontroller

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

        // Most albums Cover Flow holds, in History and Favorites alike;
        // 0 = unlimited.
        val COVER_FLOW_LIMIT = intPreferencesKey("cover_flow_limit")

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

        // Cover Flow's "glass table" look, each 0..1: how strong/sharp the
        // album reflections are, and how visible the glass surface under
        // them is. 0 is the original subtle look; both default to 0.85.
        val COVER_FLOW_REFLECTION = floatPreferencesKey("cover_flow_reflection")
        val COVER_FLOW_GLASS_TABLE = floatPreferencesKey("cover_flow_glass_table")

        // Strength (0..1) of the light falling across Cover Flow's glass
        // table from the left side.
        val COVER_FLOW_SIDE_LIGHT = floatPreferencesKey("cover_flow_side_light")

        // Which track cards Cover Flow shows while music plays: Previous
        // (bottom left), and Now Playing / Next Up (bottom right, taking
        // turns when both are shown).
        val COVER_FLOW_HIDE_PREVIOUS = booleanPreferencesKey("cover_flow_hide_previous")
        val COVER_FLOW_HIDE_NEXT_UP = booleanPreferencesKey("cover_flow_hide_next_up")
        val COVER_FLOW_HIDE_NOW_PLAYING = booleanPreferencesKey("cover_flow_hide_now_playing")

        // Whether the Now Playing screen shows its Previous / Next Up
        // track chips - independent of Cover Flow's own cards above.
        val NOW_PLAYING_HIDE_PREVIOUS = booleanPreferencesKey("now_playing_hide_previous")
        val NOW_PLAYING_HIDE_NEXT_UP = booleanPreferencesKey("now_playing_hide_next_up")
        // Legacy on/off clock toggle, superseded by NOW_PLAYING_CLOCK_MODE
        // (read only to migrate: on -> ALL_MODES, off -> OFF).
        val NOW_PLAYING_SHOW_CLOCK = booleanPreferencesKey("now_playing_show_clock")
        // Stores a NowPlayingClockMode's name (see DashboardUI.kt)
        val NOW_PLAYING_CLOCK_MODE = stringPreferencesKey("now_playing_clock_mode")

        // Experimental: in TV mode, unmute the speaker when the HA media
        // player's time remaining jumps up - a sign a commercial break just
        // ended (see SonosDashboardManager.maybeAutoUnmuteAfterAds).
        val AUTO_UNMUTE_AFTER_ADS = booleanPreferencesKey("auto_unmute_after_ads")

        // How long TV mode sits on the Now Playing screen (actively playing)
        // before dropping into the TV Ambient screensaver, in seconds.
        val TV_SCREENSAVER_DELAY_SECONDS = intPreferencesKey("tv_screensaver_delay_seconds")

        // Filename of the one tv_fallback image TV mode always shows, or ""
        // to rotate randomly through all of them (see TvFallbackArt.kt).
        val TV_FALLBACK_IMAGE = stringPreferencesKey("tv_fallback_image")

        // Sonos never reports a real artist for album favorites (only
        // title/resource/class), so this caches artist names learned by
        // observing a favorited album actually play, keyed by the
        // favorite's playUri - backfilled into the Favorites display.
        val FAVORITE_ARTIST_CACHE_JSON = stringPreferencesKey("favorite_artist_cache_json")

        const val TV_SCREENSAVER_DELAY_MIN_SECONDS = 30
        const val TV_SCREENSAVER_DELAY_MAX_SECONDS = 1800
        const val TV_SCREENSAVER_DELAY_DEFAULT_SECONDS = 30

        const val COVER_FLOW_LIMIT_UNLIMITED = 0
        const val COVER_FLOW_LIMIT_DEFAULT = 100
        val COVER_FLOW_LIMIT_OPTIONS = listOf(50, 100, 250, 500, COVER_FLOW_LIMIT_UNLIMITED)

        const val COVER_FLOW_REFLECTION_DEFAULT = 0.85f
        const val COVER_FLOW_GLASS_TABLE_DEFAULT = 0.85f
        const val COVER_FLOW_SIDE_LIGHT_DEFAULT = 0.5f
    }

    val sonosSpeaker: Flow<String> = context.dataStore.data.map { it[SELECTED_SONOS_SPEAKER] ?: "" }
    val haUrl: Flow<String> = context.dataStore.data.map { it[HA_SERVER_URL] ?: "" }
    val haToken: Flow<String> = context.dataStore.data.map { it[HA_ACCESS_TOKEN] ?: "" }
    val selectedLight: Flow<String> = context.dataStore.data.map { it[SELECTED_LIGHT_ENTITY] ?: "" }
    val selectedClimate: Flow<String> = context.dataStore.data.map { it[SELECTED_CLIMATE_ENTITY] ?: "" }
    val selectedHaMediaPlayer: Flow<String> = context.dataStore.data.map { it[SELECTED_HA_MEDIA_PLAYER] ?: "" }
    val coverFlowMode: Flow<String> = context.dataStore.data.map { it[COVER_FLOW_MODE] ?: "history" }
    
    val coverFlowLimit: Flow<Int> = context.dataStore.data.map { it[COVER_FLOW_LIMIT] ?: COVER_FLOW_LIMIT_DEFAULT }

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

    val coverFlowReflection: Flow<Float> = context.dataStore.data.map { it[COVER_FLOW_REFLECTION] ?: COVER_FLOW_REFLECTION_DEFAULT }

    suspend fun setCoverFlowReflection(strength: Float) {
        context.dataStore.edit { it[COVER_FLOW_REFLECTION] = strength }
    }

    val coverFlowGlassTable: Flow<Float> = context.dataStore.data.map { it[COVER_FLOW_GLASS_TABLE] ?: COVER_FLOW_GLASS_TABLE_DEFAULT }

    suspend fun setCoverFlowGlassTable(strength: Float) {
        context.dataStore.edit { it[COVER_FLOW_GLASS_TABLE] = strength }
    }

    val coverFlowSideLight: Flow<Float> = context.dataStore.data.map { it[COVER_FLOW_SIDE_LIGHT] ?: COVER_FLOW_SIDE_LIGHT_DEFAULT }

    suspend fun setCoverFlowSideLight(strength: Float) {
        context.dataStore.edit { it[COVER_FLOW_SIDE_LIGHT] = strength }
    }

    val coverFlowHidePrevious: Flow<Boolean> = context.dataStore.data.map { it[COVER_FLOW_HIDE_PREVIOUS] ?: false }

    suspend fun setCoverFlowHidePrevious(hide: Boolean) {
        context.dataStore.edit { it[COVER_FLOW_HIDE_PREVIOUS] = hide }
    }

    val coverFlowHideNextUp: Flow<Boolean> = context.dataStore.data.map { it[COVER_FLOW_HIDE_NEXT_UP] ?: false }

    suspend fun setCoverFlowHideNextUp(hide: Boolean) {
        context.dataStore.edit { it[COVER_FLOW_HIDE_NEXT_UP] = hide }
    }

    val coverFlowHideNowPlaying: Flow<Boolean> = context.dataStore.data.map { it[COVER_FLOW_HIDE_NOW_PLAYING] ?: false }

    suspend fun setCoverFlowHideNowPlaying(hide: Boolean) {
        context.dataStore.edit { it[COVER_FLOW_HIDE_NOW_PLAYING] = hide }
    }

    val nowPlayingHidePrevious: Flow<Boolean> = context.dataStore.data.map { it[NOW_PLAYING_HIDE_PREVIOUS] ?: false }

    suspend fun setNowPlayingHidePrevious(hide: Boolean) {
        context.dataStore.edit { it[NOW_PLAYING_HIDE_PREVIOUS] = hide }
    }

    val nowPlayingHideNextUp: Flow<Boolean> = context.dataStore.data.map { it[NOW_PLAYING_HIDE_NEXT_UP] ?: false }

    suspend fun setNowPlayingHideNextUp(hide: Boolean) {
        context.dataStore.edit { it[NOW_PLAYING_HIDE_NEXT_UP] = hide }
    }

    // Stored as the enum's name; when unset, carries over the old on/off
    // toggle so anyone who had the clock on keeps seeing it everywhere.
    val nowPlayingClockMode: Flow<NowPlayingClockMode> = context.dataStore.data.map { prefs ->
        val name = prefs[NOW_PLAYING_CLOCK_MODE]
        NowPlayingClockMode.entries.find { it.name == name }
            ?: if (prefs[NOW_PLAYING_SHOW_CLOCK] == true) NowPlayingClockMode.ALL_MODES else NowPlayingClockMode.OFF
    }

    suspend fun setNowPlayingClockMode(mode: NowPlayingClockMode) {
        context.dataStore.edit { it[NOW_PLAYING_CLOCK_MODE] = mode.name }
    }

    val autoUnmuteAfterAds: Flow<Boolean> = context.dataStore.data.map { it[AUTO_UNMUTE_AFTER_ADS] ?: false }

    suspend fun setAutoUnmuteAfterAds(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_UNMUTE_AFTER_ADS] = enabled }
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

    val tvFallbackImage: Flow<String> = context.dataStore.data.map { it[TV_FALLBACK_IMAGE] ?: "" }

    suspend fun setTvFallbackImage(name: String) {
        context.dataStore.edit { it[TV_FALLBACK_IMAGE] = name }
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
        fRadio: Boolean = true,
        coverFlowLimit: Int = COVER_FLOW_LIMIT_DEFAULT
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
            prefs[COVER_FLOW_LIMIT] = coverFlowLimit
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
