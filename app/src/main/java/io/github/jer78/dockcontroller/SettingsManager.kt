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
        // Legacy single light/climate pickers, superseded by HA_WIDGETS
        // (read only to migrate: climate -> position 1, light -> position 2,
        // the order they used to appear in).
        val SELECTED_LIGHT_ENTITY = stringPreferencesKey("ha_light_entity")
        val SELECTED_CLIMATE_ENTITY = stringPreferencesKey("ha_climate_entity")
        // Now Playing's Home Assistant widgets: a JSON array of
        // MAX_HA_WIDGETS positions, each null or {"type": <domain>, "entity": <id>}.
        val HA_WIDGETS = stringPreferencesKey("ha_widgets")
        val SELECTED_HA_MEDIA_PLAYER = stringPreferencesKey("ha_media_player")
        // Optional TMDB API key (v3 key or v4 read access token) for TV show
        // logos; without one, logos come from TVmaze (see TvShowLogos).
        val TMDB_API_KEY = stringPreferencesKey("tmdb_api_key")
        // No longer used (History mode and the Cover Flow limit are gone) -
        // kept so saving Settings can clear them out.
        val COVER_FLOW_MODE = stringPreferencesKey("cover_flow_mode")
        // Whether Cover Flow last showed the grid rather than the carousel,
        // so it opens that way again.
        val COVER_FLOW_GRID_VIEW = booleanPreferencesKey("cover_flow_grid_view")
        // Cover Flow / grid album order: by artist (true) or album title.
        val COVER_FLOW_SORT_BY_ARTIST = booleanPreferencesKey("cover_flow_sort_by_artist")
        
        // Favorite Filters
        val FILTER_ALBUMS = booleanPreferencesKey("filter_albums")
        val FILTER_PLAYLISTS = booleanPreferencesKey("filter_playlists")
        val FILTER_RADIO = booleanPreferencesKey("filter_radio")

        val ALBUM_HISTORY_JSON = stringPreferencesKey("album_history_json")

        // Most albums Cover Flow holds, in History and Favorites alike (in
        // Favorites, per kind: albums, playlists, radio); 0 = unlimited.
        val COVER_FLOW_LIMIT = intPreferencesKey("cover_flow_limit")

        // Mic-based spectrum analyzer shown behind Now Playing (see SpectrumAnalyzer.kt)
        val SHOW_SPECTRUM_ANALYZER = booleanPreferencesKey("show_spectrum_analyzer")
        // Stores a SpectrumColorScheme's name (see DashboardUI.kt)
        val SPECTRUM_COLOR_SCHEME = stringPreferencesKey("spectrum_color_scheme")
        // Fraction (0-1) of the screen height, measured from the bottom, the
        // visualizer occupies when it's not confined to the album art.
        val SPECTRUM_HEIGHT_PERCENT = floatPreferencesKey("spectrum_height_percent")
        // Now Playing's blurred background, in dp; TV's is
        // BACKGROUND_BLUR_SAME_AS_MUSIC unless set on its own.
        val MUSIC_BACKGROUND_BLUR = floatPreferencesKey("music_background_blur")
        val TV_BACKGROUND_BLUR = floatPreferencesKey("tv_background_blur")
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
        // TV mode's clock takes turns with when the show ends (on by default).
        val SHOW_ENDS_AT = booleanPreferencesKey("show_ends_at")
        // Stores a CoverFlowWidgetsMode's name, and the binary_sensor that
        // tells it someone's near (blank = touch only).
        val COVER_FLOW_WIDGETS_MODE = stringPreferencesKey("cover_flow_widgets_mode")
        val COVER_FLOW_MOTION_SENSOR = stringPreferencesKey("cover_flow_motion_sensor")

        // Experimental: in TV mode, unmute the speaker when the HA media
        // player's time remaining jumps up - a sign a commercial break just
        // ended (see SonosDashboardManager.maybeAutoUnmuteAfterAds).
        val AUTO_UNMUTE_AFTER_ADS = booleanPreferencesKey("auto_unmute_after_ads")
        // TV mode: replace Home Assistant's artwork with the show's logo or
        // poster from TMDB / TVmaze (see TvShowLogos). Off uses HA's images.
        val TV_SHOW_IMAGES = booleanPreferencesKey("tv_show_images")
        // Experimental: TV mode artwork for YouTube videos, from the video
        // ID, or found with the optional YouTube Data API key (see
        // YouTubeThumbnails).
        val YOUTUBE_THUMBNAILS = booleanPreferencesKey("youtube_thumbnails")
        val YOUTUBE_API_KEY = stringPreferencesKey("youtube_api_key")
        // This device's YouTube searches on YOUTUBE_USAGE_DAY (Google's
        // Pacific-time quota day), whether Google said the quota ran out
        // that day, and how the last search went (a
        // YouTubeThumbnails.SearchResult name) and what for.
        val YOUTUBE_USAGE_DAY = stringPreferencesKey("youtube_usage_day")
        val YOUTUBE_SEARCHES_TODAY = intPreferencesKey("youtube_searches_today")
        val YOUTUBE_QUOTA_REACHED = booleanPreferencesKey("youtube_quota_reached")
        val YOUTUBE_LAST_RESULT = stringPreferencesKey("youtube_last_result")
        val YOUTUBE_LAST_QUERY = stringPreferencesKey("youtube_last_query")

        // When a queued album plays to the end, start another random album
        // from Sonos Favorites (see SonosDashboardManager.maybeContinueWithRandomAlbum).
        val CONTINUE_WITH_RANDOM_ALBUM = booleanPreferencesKey("continue_with_random_album")

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
        // The grid's recently played favorites taking a turn at the front
        // (on by default), and the last few favorites played: a JSON array
        // of {"key": <favoriteKey>, "at": <epoch ms>}, newest first.
        val GRID_RECENTLY_PLAYED = booleanPreferencesKey("grid_recently_played")
        val RECENTLY_PLAYED_FAVORITES = stringPreferencesKey("recently_played_favorites")

        const val TV_SCREENSAVER_DELAY_MIN_SECONDS = 30
        const val TV_SCREENSAVER_DELAY_MAX_SECONDS = 1800
        const val BACKGROUND_BLUR_DEFAULT = 40f
        const val BACKGROUND_BLUR_MAX = 80f
        // TV's blur follows the music one.
        const val BACKGROUND_BLUR_SAME_AS_MUSIC = -1f
        const val TV_SCREENSAVER_DELAY_DEFAULT_SECONDS = 30


        const val COVER_FLOW_REFLECTION_DEFAULT = 0.85f
        const val COVER_FLOW_GLASS_TABLE_DEFAULT = 0.85f
        const val COVER_FLOW_SIDE_LIGHT_DEFAULT = 0.5f
    }

    val sonosSpeaker: Flow<String> = context.dataStore.data.map { it[SELECTED_SONOS_SPEAKER] ?: "" }
    val haUrl: Flow<String> = context.dataStore.data.map { it[HA_SERVER_URL] ?: "" }
    val haToken: Flow<String> = context.dataStore.data.map { it[HA_ACCESS_TOKEN] ?: "" }
    // Always MAX_HA_WIDGETS long, null for an empty position.
    val haWidgets: Flow<List<HaWidgetSlot?>> = context.dataStore.data.map { prefs ->
        val json = prefs[HA_WIDGETS]
        if (json == null) {
            val legacy = listOfNotNull(
                prefs[SELECTED_CLIMATE_ENTITY]?.takeIf { it.isNotBlank() }?.let { HaWidgetSlot(HaWidgetType.CLIMATE, it) },
                prefs[SELECTED_LIGHT_ENTITY]?.takeIf { it.isNotBlank() }?.let { HaWidgetSlot(HaWidgetType.LIGHT, it) }
            )
            List(MAX_HA_WIDGETS) { legacy.getOrNull(it) }
        } else {
            val array = try { JSONArray(json) } catch (e: Exception) { JSONArray() }
            List(MAX_HA_WIDGETS) { i ->
                array.optJSONObject(i)?.let { obj ->
                    val type = HaWidgetType.fromDomain(obj.optString("type"))
                    val entity = obj.optString("entity")
                    if (type != null && entity.isNotBlank()) {
                        HaWidgetSlot(type, entity, obj.optBoolean("show_label", false), obj.optString("label", ""))
                    } else null
                }
            }
        }
    }
    val selectedHaMediaPlayer: Flow<String> = context.dataStore.data.map { it[SELECTED_HA_MEDIA_PLAYER] ?: "" }
    val tmdbApiKey: Flow<String> = context.dataStore.data.map { it[TMDB_API_KEY] ?: "" }
    

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

    val musicBackgroundBlur: Flow<Float> = context.dataStore.data.map { it[MUSIC_BACKGROUND_BLUR] ?: BACKGROUND_BLUR_DEFAULT }

    suspend fun setMusicBackgroundBlur(dp: Float) {
        context.dataStore.edit { it[MUSIC_BACKGROUND_BLUR] = dp }
    }

    val tvBackgroundBlur: Flow<Float> = context.dataStore.data.map { it[TV_BACKGROUND_BLUR] ?: BACKGROUND_BLUR_SAME_AS_MUSIC }

    suspend fun setTvBackgroundBlur(dp: Float) {
        context.dataStore.edit { it[TV_BACKGROUND_BLUR] = dp }
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

    val coverFlowGridView: Flow<Boolean> = context.dataStore.data.map { it[COVER_FLOW_GRID_VIEW] ?: false }

    val gridRecentlyPlayed: Flow<Boolean> = context.dataStore.data.map { it[GRID_RECENTLY_PLAYED] ?: true }

    suspend fun setGridRecentlyPlayed(enabled: Boolean) {
        context.dataStore.edit { it[GRID_RECENTLY_PLAYED] = enabled }
    }

    val recentlyPlayedFavorites: Flow<List<Pair<String, Long>>> = context.dataStore.data.map { prefs ->
        try {
            val array = JSONArray(prefs[RECENTLY_PLAYED_FAVORITES] ?: "[]")
            (0 until array.length()).map { i ->
                val entry = array.getJSONObject(i)
                entry.getString("key") to entry.getLong("at")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun saveRecentlyPlayedFavorites(played: List<Pair<String, Long>>) {
        val array = JSONArray()
        played.forEach { (key, at) -> array.put(JSONObject().put("key", key).put("at", at)) }
        context.dataStore.edit { it[RECENTLY_PLAYED_FAVORITES] = array.toString() }
    }

    val coverFlowSortByArtist: Flow<Boolean> = context.dataStore.data.map { it[COVER_FLOW_SORT_BY_ARTIST] ?: true }

    suspend fun setCoverFlowSortByArtist(byArtist: Boolean) {
        context.dataStore.edit { it[COVER_FLOW_SORT_BY_ARTIST] = byArtist }
    }

    suspend fun setCoverFlowGridView(grid: Boolean) {
        context.dataStore.edit { it[COVER_FLOW_GRID_VIEW] = grid }
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

    val showEndsAt: Flow<Boolean> = context.dataStore.data.map { it[SHOW_ENDS_AT] ?: true }

    suspend fun setShowEndsAt(enabled: Boolean) {
        context.dataStore.edit { it[SHOW_ENDS_AT] = enabled }
    }

    val coverFlowWidgetsMode: Flow<CoverFlowWidgetsMode> = context.dataStore.data.map { prefs ->
        CoverFlowWidgetsMode.entries.find { it.name == prefs[COVER_FLOW_WIDGETS_MODE] } ?: CoverFlowWidgetsMode.NEAR
    }

    suspend fun setCoverFlowWidgetsMode(mode: CoverFlowWidgetsMode) {
        context.dataStore.edit { it[COVER_FLOW_WIDGETS_MODE] = mode.name }
    }

    val coverFlowMotionSensor: Flow<String> = context.dataStore.data.map { it[COVER_FLOW_MOTION_SENSOR] ?: "" }

    suspend fun setCoverFlowMotionSensor(entityId: String) {
        context.dataStore.edit { it[COVER_FLOW_MOTION_SENSOR] = entityId.trim() }
    }

    val autoUnmuteAfterAds: Flow<Boolean> = context.dataStore.data.map { it[AUTO_UNMUTE_AFTER_ADS] ?: false }

    suspend fun setAutoUnmuteAfterAds(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_UNMUTE_AFTER_ADS] = enabled }
    }

    val tvShowImages: Flow<Boolean> = context.dataStore.data.map { it[TV_SHOW_IMAGES] ?: true }

    suspend fun setTvShowImages(enabled: Boolean) {
        context.dataStore.edit { it[TV_SHOW_IMAGES] = enabled }
    }

    val youTubeThumbnails: Flow<Boolean> = context.dataStore.data.map { it[YOUTUBE_THUMBNAILS] ?: false }

    suspend fun setYouTubeThumbnails(enabled: Boolean) {
        context.dataStore.edit { it[YOUTUBE_THUMBNAILS] = enabled }
    }

    val youTubeApiKey: Flow<String> = context.dataStore.data.map { it[YOUTUBE_API_KEY] ?: "" }

    // Today's count and quota flag start over once Google's quota day has
    // moved on; the last result is kept.
    val youTubeUsage: Flow<YouTubeUsage> = context.dataStore.data.map { prefs ->
        val today = prefs[YOUTUBE_USAGE_DAY] == YouTubeThumbnails.quotaDay()
        YouTubeUsage(
            searchesToday = if (today) prefs[YOUTUBE_SEARCHES_TODAY] ?: 0 else 0,
            quotaReached = today && prefs[YOUTUBE_QUOTA_REACHED] == true,
            lastResult = prefs[YOUTUBE_LAST_RESULT]?.let { name ->
                YouTubeThumbnails.SearchResult.entries.find { it.name == name }
            },
            lastQuery = prefs[YOUTUBE_LAST_QUERY] ?: ""
        )
    }

    // Successful searches (found or not) are what count against the quota.
    suspend fun recordYouTubeSearch(result: YouTubeThumbnails.SearchResult, query: String) {
        context.dataStore.edit { prefs ->
            val day = YouTubeThumbnails.quotaDay()
            if (prefs[YOUTUBE_USAGE_DAY] != day) {
                prefs[YOUTUBE_USAGE_DAY] = day
                prefs[YOUTUBE_SEARCHES_TODAY] = 0
                prefs[YOUTUBE_QUOTA_REACHED] = false
            }
            when (result) {
                YouTubeThumbnails.SearchResult.FOUND, YouTubeThumbnails.SearchResult.NO_MATCH ->
                    prefs[YOUTUBE_SEARCHES_TODAY] = (prefs[YOUTUBE_SEARCHES_TODAY] ?: 0) + 1
                YouTubeThumbnails.SearchResult.QUOTA_EXCEEDED -> prefs[YOUTUBE_QUOTA_REACHED] = true
                else -> {}
            }
            prefs[YOUTUBE_LAST_RESULT] = result.name
            prefs[YOUTUBE_LAST_QUERY] = query
        }
    }

    val continueWithRandomAlbum: Flow<Boolean> = context.dataStore.data.map { it[CONTINUE_WITH_RANDOM_ALBUM] ?: false }

    suspend fun setContinueWithRandomAlbum(enabled: Boolean) {
        context.dataStore.edit { it[CONTINUE_WITH_RANDOM_ALBUM] = enabled }
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

    suspend fun saveSettings(
        speaker: String,
        url: String,
        token: String,
        haWidgets: List<HaWidgetSlot?>,
        haMediaPlayer: String,
        tmdbApiKey: String,
        youTubeApiKey: String,
        fAlbums: Boolean = true,
        fPlaylists: Boolean = true,
        fRadio: Boolean = true
    ) {
        context.dataStore.edit { prefs ->
            prefs[SELECTED_SONOS_SPEAKER] = speaker
            prefs[HA_SERVER_URL] = url
            prefs[HA_ACCESS_TOKEN] = token
            prefs[HA_WIDGETS] = haWidgetsJson(haWidgets)
            prefs[SELECTED_HA_MEDIA_PLAYER] = haMediaPlayer
            prefs[TMDB_API_KEY] = tmdbApiKey.trim()
            prefs[YOUTUBE_API_KEY] = youTubeApiKey.trim()
            prefs[FILTER_ALBUMS] = fAlbums
            prefs[FILTER_PLAYLISTS] = fPlaylists
            prefs[FILTER_RADIO] = fRadio
            prefs.remove(COVER_FLOW_MODE)
            prefs.remove(COVER_FLOW_LIMIT)
            prefs.remove(ALBUM_HISTORY_JSON)
        }
    }

    // For Now Playing's drag-to-reorder - the rest of Settings is untouched.
    suspend fun saveHaWidgets(haWidgets: List<HaWidgetSlot?>) {
        context.dataStore.edit { it[HA_WIDGETS] = haWidgetsJson(haWidgets) }
    }

    private fun haWidgetsJson(haWidgets: List<HaWidgetSlot?>): String = JSONArray().apply {
        for (i in 0 until MAX_HA_WIDGETS) {
            val slot = haWidgets.getOrNull(i)?.takeIf { it.entityId.isNotBlank() }
            put(slot?.let {
                JSONObject()
                    .put("type", it.type.domain)
                    .put("entity", it.entityId.trim())
                    .put("show_label", it.showLabel)
                    .put("label", it.label.trim())
            } ?: JSONObject.NULL)
        }
    }.toString()

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


}
