package io.github.jer78.dockcontroller

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// A TV show's title logo, shown in place of the artwork in TV mode.
// transparent: a cut-out PNG (TMDB) that sits on the artwork; otherwise an
// image with its own white background (TVmaze), shown on a white card.
data class TvShowLogo(val url: String, val transparent: Boolean)

// "Severance S2 E3", "Severance - S02E03 - Who Is Alive?", "Severance · S2 · E3",
// "Severance Season 2 Episode 3" - the show name is everything before the
// season/episode marker.
private val EPISODE_MARKER = Regex(
    """^(.*?)[\s\-–—:·|,(\[]*\b(?:S\d{1,2}\s*[·.:\-_ ]?\s*E\d{1,3}|Season\s*\d{1,2}\W{0,3}Episode\s*\d{1,3})\b""",
    RegexOption.IGNORE_CASE
)

// The show being watched, from the HA media player's series title when the
// integration reports one, otherwise read from an "S2 E3"-style title.
// Null when it doesn't look like a TV episode.
fun detectTvShowName(title: String, seriesTitle: String): String? {
    if (seriesTitle.isNotBlank()) return seriesTitle.trim()
    val match = EPISODE_MARKER.find(title) ?: return null
    return match.groupValues[1].trim().trimEnd('-', '–', '—', ':', '·', '|', ',', '(', '[').trim()
        .takeIf { it.length >= 2 }
}

// Looks up show logos - TMDB (with the user's free API key, which has
// transparent logos) first, then TVmaze (no key needed) - and remembers the
// answer per show, so each show is only looked up once. Shows with no logo
// anywhere are retried after a while, in case it was a network blip.
class TvShowLogos {
    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    private data class Entry(val logo: TvShowLogo?, val atMs: Long)
    private val cache = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    // Adding or changing the TMDB key looks every show up again.
    private fun cacheKey(show: String, tmdbKey: String) = "${show.lowercase()}|${tmdbKey.isNotBlank()}"

    fun cached(show: String, tmdbKey: String): TvShowLogo? = cache[cacheKey(show, tmdbKey)]?.logo

    // True when a lookup should start now; callers then call lookUp().
    fun claimLookup(show: String, tmdbKey: String): Boolean {
        val key = cacheKey(show, tmdbKey)
        val entry = cache[key]
        if (entry != null && (entry.logo != null || System.currentTimeMillis() - entry.atMs < RETRY_MISS_MS)) return false
        return inFlight.add(key)
    }

    suspend fun lookUp(show: String, tmdbKey: String): TvShowLogo? = withContext(Dispatchers.IO) {
        val key = cacheKey(show, tmdbKey)
        try {
            val logo = (if (tmdbKey.isNotBlank()) fromTmdb(show, tmdbKey.trim()) else null) ?: fromTvMaze(show)
            cache[key] = Entry(logo, System.currentTimeMillis())
            logo
        } finally {
            inFlight.remove(key)
        }
    }

    private fun getJson(url: String, bearer: String? = null): String? = try {
        val request = Request.Builder().url(url).apply {
            if (bearer != null) addHeader("Authorization", "Bearer $bearer")
        }.build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else {
                Log.w("TvShowLogos", "HTTP ${response.code} for ${url.substringBefore('?')}")
                null
            }
        }
    } catch (e: Exception) {
        Log.w("TvShowLogos", "Lookup failed: ${e.message}")
        null
    }

    // TMDB takes either a v3 API key (as ?api_key=) or a v4 read access
    // token (a long "eyJ..." JWT, as a Bearer header) - accept whichever
    // the user pasted.
    private fun tmdbGet(path: String, params: Map<String, String>, tmdbKey: String): JSONObject? {
        val isToken = tmdbKey.startsWith("eyJ")
        val url = "https://api.themoviedb.org/3$path".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            if (!isToken) addQueryParameter("api_key", tmdbKey)
        }.build().toString()
        return getJson(url, bearer = if (isToken) tmdbKey else null)?.let { JSONObject(it) }
    }

    private fun fromTmdb(show: String, tmdbKey: String): TvShowLogo? {
        val results = tmdbGet("/search/tv", mapOf("query" to show, "include_adult" to "false"), tmdbKey)
            ?.optJSONArray("results") ?: return null
        val id = results.optJSONObject(0)?.optInt("id") ?: return null
        val logos = tmdbGet("/tv/$id/images", mapOf("include_image_language" to "en,null"), tmdbKey)
            ?.optJSONArray("logos") ?: return null
        // English first, then language-neutral, each by votes. SVG logos
        // are skipped - the image loader only handles bitmaps.
        val best = logos.objects()
            .filter { it.optString("file_path").endsWith(".png", ignoreCase = true) }
            .sortedWith(
                compareBy<JSONObject> { if (it.optString("iso_639_1") == "en") 0 else 1 }
                    .thenByDescending { it.optDouble("vote_average", 0.0) }
            )
            .firstOrNull() ?: return null
        return TvShowLogo("https://image.tmdb.org/t/p/w500${best.optString("file_path")}", transparent = true)
    }

    private fun fromTvMaze(show: String): TvShowLogo? {
        val url = "https://api.tvmaze.com/singlesearch/shows".toHttpUrl().newBuilder()
            .addQueryParameter("q", show)
            .addQueryParameter("embed", "images")
            .build().toString()
        val json = getJson(url)?.let { JSONObject(it) } ?: return null
        val images = json.optJSONObject("_embedded")?.optJSONArray("images") ?: return null
        val typography = images.objects().firstOrNull { it.optString("type") == "typography" } ?: return null
        val logoUrl = typography.optJSONObject("resolutions")?.optJSONObject("original")?.optString("url")
        return logoUrl?.takeIf { it.isNotBlank() }?.let { TvShowLogo(it, transparent = false) }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    companion object {
        private const val RETRY_MISS_MS = 30 * 60 * 1000L
    }
}
