package io.github.jer78.dockcontroller

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// An album by an artist like the one playing, for the suggestions screensaver.
data class SuggestedAlbum(val artist: String, val album: String, val artUrl: String)

// Settings > Now Playing > Artist Suggestions: artists Last.fm says are
// similar to the one playing, each with their most played album - leaving
// out artists already in the favorites, since the point is finding new ones.
// Answers are kept for the session, so each artist is only looked up once.
// Also remembers when suggestions were last shown, shared by the app and the
// screensaver: they come back after SHOW_AGAIN_MS, or for a new artist.
object ArtistSuggestions {
    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    // Lowercased artist -> similar artists, best match first.
    private val similar = ConcurrentHashMap<String, List<String>>()
    // Lowercased artist -> their top album with artwork, or NONE.
    private val topAlbums = ConcurrentHashMap<String, SuggestedAlbum>()
    private val NONE = SuggestedAlbum("", "", "")

    @Volatile private var lastShownArtist = ""
    @Volatile private var lastShownAtMs = 0L

    // The suggestions last shown, and for which artist - Cover Flow's track
    // cards offer them again while that artist plays.
    var latest by mutableStateOf<Pair<String, List<SuggestedAlbum>>?>(null)
        private set

    fun latestFor(artist: String): List<SuggestedAlbum> =
        latest?.takeIf { it.first.equals(artist, ignoreCase = true) }?.second.orEmpty()

    // When suggestions for this artist may next show.
    fun nextShowAtMs(artist: String): Long =
        if (artist.equals(lastShownArtist, ignoreCase = true)) lastShownAtMs + SHOW_AGAIN_MS else 0L

    fun markShown(artist: String, suggestions: List<SuggestedAlbum>) {
        lastShownArtist = artist
        lastShownAtMs = System.currentTimeMillis()
        latest = artist to suggestions
    }

    enum class KeyCheck { OK, REJECTED, UNREACHABLE }

    // For Settings: whether Last.fm accepts this key.
    suspend fun checkKey(apiKey: String): KeyCheck = withContext(Dispatchers.IO) {
        val url = "https://ws.audioscrobbler.com/2.0/".toHttpUrl().newBuilder()
            .addQueryParameter("method", "chart.gettopartists")
            .addQueryParameter("limit", "1")
            .addQueryParameter("api_key", apiKey.trim())
            .addQueryParameter("format", "json")
            .build()
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val json = response.body?.string()?.let { runCatching { JSONObject(it) }.getOrNull() }
                when {
                    json == null -> KeyCheck.UNREACHABLE
                    // 10: invalid key, 26: suspended key.
                    json.optInt("error") == 10 || json.optInt("error") == 26 -> KeyCheck.REJECTED
                    response.isSuccessful && !json.has("error") -> KeyCheck.OK
                    else -> KeyCheck.UNREACHABLE
                }
            }
        } catch (e: Exception) {
            KeyCheck.UNREACHABLE
        }
    }

    // Up to `count` albums, picked at random from the closest matches so
    // each showing differs. Empty when there's nothing (new) to suggest or
    // Last.fm couldn't be reached.
    suspend fun suggest(
        artist: String,
        apiKey: String,
        favorites: List<AlbumHistoryItem>,
        count: Int = 3
    ): List<SuggestedAlbum> = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        if (artist.isBlank() || key.isBlank()) return@withContext emptyList()
        val known = favorites.map { it.artist.lowercase() }.toSet() + artist.lowercase()
        val similarArtists = similar[artist.lowercase()]
            ?: fetchSimilar(artist, key)?.also { similar[artist.lowercase()] = it }
            ?: return@withContext emptyList()
        val picks = mutableListOf<SuggestedAlbum>()
        for (name in similarArtists.filter { it.lowercase() !in known }.take(CANDIDATES).shuffled()) {
            val album = topAlbums[name.lowercase()]
                ?: (fetchTopAlbum(name, key) ?: continue).also { topAlbums[name.lowercase()] = it }
            if (album != NONE) picks += album
            if (picks.size == count) break
        }
        picks
    }

    // Null when Last.fm couldn't be asked (so it's tried again later), as
    // opposed to an empty answer.
    private fun fetchSimilar(artist: String, apiKey: String): List<String>? {
        val json = get("artist.getsimilar", artist, apiKey, limit = 30) ?: return null
        val artists = json.optJSONObject("similarartists")?.optJSONArray("artist") ?: return emptyList()
        return (0 until artists.length()).mapNotNull { artists.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
    }

    // The first of their top albums with real artwork - Last.fm's top
    // albums include untitled "(null)" entries and blank star placeholders.
    private fun fetchTopAlbum(artist: String, apiKey: String): SuggestedAlbum? {
        val json = get("artist.gettopalbums", artist, apiKey, limit = 5) ?: return null
        val albums = json.optJSONObject("topalbums")?.optJSONArray("album") ?: return NONE
        for (i in 0 until albums.length()) {
            val album = albums.optJSONObject(i) ?: continue
            val name = album.optString("name")
            if (name.isBlank() || name == "(null)") continue
            val images = album.optJSONArray("image") ?: continue
            val art = (0 until images.length()).mapNotNull { images.optJSONObject(it) }
                .lastOrNull { it.optString("size") == "extralarge" || it.optString("size") == "large" }
                ?.optString("#text").orEmpty()
            if (art.isBlank() || PLACEHOLDER_ART in art) continue
            return SuggestedAlbum(artist = album.optJSONObject("artist")?.optString("name")?.ifBlank { null } ?: artist, album = name, artUrl = art)
        }
        return NONE
    }

    private fun get(method: String, artist: String, apiKey: String, limit: Int): JSONObject? {
        val url = "https://ws.audioscrobbler.com/2.0/".toHttpUrl().newBuilder()
            .addQueryParameter("method", method)
            .addQueryParameter("artist", artist)
            .addQueryParameter("autocorrect", "1")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("format", "json")
            .build()
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val json = response.body?.string()?.let { JSONObject(it) }
                // Errors come back as {"error": <code>, "message": ...},
                // sometimes with a 200 - a bad key is code 10.
                if (!response.isSuccessful || json == null || json.has("error")) {
                    Log.w("ArtistSuggestions", "$method HTTP ${response.code}: ${json?.optString("message")}")
                    null
                } else json
            }
        } catch (e: Exception) {
            Log.w("ArtistSuggestions", "$method failed: ${e.message}")
            null
        }
    }

    // How long after showing before the same artist's suggestions come back.
    const val SHOW_AGAIN_MS = 10 * 60 * 1000L
    // The closest matches the three are picked from.
    private const val CANDIDATES = 12
    // Last.fm's grey star, its stand-in for missing artwork.
    private const val PLACEHOLDER_ART = "2a96cbd8b46e442fc41c2b86b821562f"
}
