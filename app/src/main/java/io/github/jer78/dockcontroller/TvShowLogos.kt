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

// A TV show's title logo (TMDB), shown in place of the artwork in TV mode -
// or, from TVmaze, the show's poster.
// aspectRatio: the image's width / height, for a poster, so the artwork
// card can take its shape. backgroundUrl: the show's background image
// (TVmaze), shown behind the whole screen in place of the blurred artwork.
data class TvShowLogo(
    val url: String,
    val kind: Kind,
    val aspectRatio: Float = 16f / 9f,
    val backgroundUrl: String? = null
) {
    enum class Kind {
        // A cut-out PNG (TMDB) that sits on the artwork.
        TRANSPARENT_LOGO,
        // The show's poster (TVmaze), when TMDB has no logo.
        POSTER
    }
}

// "S2 E3", "S02E03", "S2 · E3", "Season 2 Episode 3", "Season 28, Ep. 41".
private const val SEASON_EPISODE =
    """(?:S\d{1,2}\s*[·.:\-_ ]?\s*E\d{1,4}|Season\s*\d{1,2}\W{0,3}(?:Episode|Ep\.?)\s*\d{1,4})"""

// "Severance S2 E3", "Severance - S02E03 - Who Is Alive?", "Severance · S2 · E3",
// "Severance Season 2 Episode 3" - the show name is everything before the
// season/episode marker.
private val EPISODE_MARKER = Regex(
    """^(.*?)[\s\-–—:·|,(\[]*\b$SEASON_EPISODE\b""",
    RegexOption.IGNORE_CASE
)
private val ANY_EPISODE_MARKER = Regex("""\b$SEASON_EPISODE\b""", RegexOption.IGNORE_CASE)

// The show being watched, from the HA media player's series title when the
// integration reports one, otherwise read from an "S2 E3"-style title. Some
// integrations put the show in the title and "Season 28, Ep. 41 ..." in the
// artist or album instead, so a marker there makes the title the show.
// Null when it doesn't look like a TV episode.
fun detectTvShowName(title: String, seriesTitle: String, artist: String = "", album: String = ""): String? {
    if (seriesTitle.isNotBlank()) return cleanShowName(seriesTitle)
    EPISODE_MARKER.find(title)?.let { match ->
        return cleanShowName(match.groupValues[1])
    }
    if (ANY_EPISODE_MARKER.containsMatchIn(artist) || ANY_EPISODE_MARKER.containsMatchIn(album)) {
        return cleanShowName(title)
    }
    return null
}

// Separators left dangling at either end ("The Traitors Canada -") make the
// logo searches find nothing, so they're trimmed off whichever way the name
// was found. Titles can use non-breaking spaces and Unicode dashes or minus
// signs that look just like the plain ones, so rather than listing
// separators, anything that can't end a show name is trimmed: only letters,
// digits and closing marks like ")", "!" or "?" are kept at the ends.
private fun canEndShowName(c: Char) = c.isLetterOrDigit() || c in ")]!?'\"’&.+*#"

private fun cleanShowName(name: String): String? =
    name.dropWhile { !it.isLetterOrDigit() && it !in "(['\"‘“#" }
        .dropLastWhile { !canEndShowName(it) }
        .takeIf { it.length >= 2 }

// Looks up show logos - TMDB (with the user's free API key, which has
// transparent logos) first, then TVmaze (no key needed). Each logo found is
// saved, images and all, in TvArtworkStore, so a show is only looked up
// once, even across restarts. Shows with no logo anywhere are retried after
// a while; a lookup that failed (timeout, no network, server error) is
// retried within a minute instead, so one slow request doesn't hide the
// show's logo for half an hour.
class TvShowLogos(private val store: TvArtworkStore) {
    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    // TVmaze's search can take 15-30 seconds when it hasn't been asked for
    // that show lately (e.g. "The Traitors"), then answers instantly once
    // warm - 10 seconds gave up on it every time.
    private val tvMazeClient = client.newBuilder().callTimeout(45, TimeUnit.SECONDS).build()

    // A request that didn't get a real answer, as opposed to "no such show".
    private class LookupFailed : Exception()

    private data class Miss(val atMs: Long, val retryMs: Long)
    private val misses = ConcurrentHashMap<String, Miss>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    // Adding or changing the TMDB key looks missed shows up again.
    private fun missKey(show: String, tmdbKey: String) = "${show.lowercase()}|${tmdbKey.isNotBlank()}"

    private fun storeKey(show: String) = "show:${show.lowercase()}"

    // A saved TVmaze poster from before a TMDB key was added doesn't count,
    // so the show gets a look at TMDB's logos.
    fun cached(show: String, tmdbKey: String): TvShowLogo? =
        store.get(storeKey(show))
            ?.takeUnless { tmdbKey.isNotBlank() && !it.withTmdbKey && it.source == SavedTvArtwork.Source.TVMAZE_POSTER }
            ?.let { store.logoFor(it) }

    // True when a lookup should start now; callers then call lookUp().
    fun claimLookup(show: String, tmdbKey: String): Boolean {
        if (cached(show, tmdbKey) != null) return false
        val key = missKey(show, tmdbKey)
        val miss = misses[key]
        if (miss != null && System.currentTimeMillis() - miss.atMs < miss.retryMs) return false
        return inFlight.add(key)
    }

    suspend fun lookUp(show: String, tmdbKey: String): TvShowLogo? = withContext(Dispatchers.IO) {
        val key = missKey(show, tmdbKey)
        var failed = false
        fun attempt(source: () -> TvShowLogo?): TvShowLogo? = try {
            source()
        } catch (e: LookupFailed) {
            failed = true
            null
        }
        try {
            val logo = (if (tmdbKey.isNotBlank()) attempt { fromTmdb(show, tmdbKey.trim()) } else null)
                ?: attempt { fromTvMaze(show) }
            if (logo == null) {
                misses[key] = Miss(System.currentTimeMillis(), if (failed) RETRY_FAILED_MS else RETRY_MISS_MS)
                return@withContext null
            }
            misses.remove(key)
            val entry = SavedTvArtwork(
                key = storeKey(show),
                name = show,
                source = if (logo.kind == TvShowLogo.Kind.TRANSPARENT_LOGO) SavedTvArtwork.Source.TMDB_LOGO
                else SavedTvArtwork.Source.TVMAZE_POSTER,
                url = logo.url,
                backgroundUrl = logo.backgroundUrl,
                aspectRatio = logo.aspectRatio,
                withTmdbKey = tmdbKey.isNotBlank()
            )
            store.save(entry)
            store.logoFor(entry)
        } finally {
            inFlight.remove(key)
        }
    }

    // Null for a real "not found" (e.g. TVmaze's 404 for no matching show);
    // throws LookupFailed when there was no real answer - a timeout, no
    // network, a server error or being rate limited.
    private fun getJson(url: String, bearer: String? = null, httpClient: OkHttpClient = client): String? {
        val request = Request.Builder().url(url).apply {
            if (bearer != null) addHeader("Authorization", "Bearer $bearer")
        }.build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) return response.body?.string()
                Log.w("TvShowLogos", "HTTP ${response.code} for ${url.substringBefore('?')}")
                if (response.code == 429 || response.code >= 500) throw LookupFailed()
                return null
            }
        } catch (e: LookupFailed) {
            throw e
        } catch (e: Exception) {
            Log.w("TvShowLogos", "Lookup failed: ${e.message}")
            throw LookupFailed()
        }
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
        return TvShowLogo("https://image.tmdb.org/t/p/w500${best.optString("file_path")}", TvShowLogo.Kind.TRANSPARENT_LOGO)
    }

    // The show's poster - the main one first - plus its background, if it
    // has one. TVmaze's logos sit on a white background, so they're not used.
    private fun fromTvMaze(show: String): TvShowLogo? {
        val url = "https://api.tvmaze.com/singlesearch/shows".toHttpUrl().newBuilder()
            .addQueryParameter("q", show)
            .addQueryParameter("embed", "images")
            .build().toString()
        val json = getJson(url, httpClient = tvMazeClient)?.let { JSONObject(it) } ?: return null
        val images = json.optJSONObject("_embedded")?.optJSONArray("images")?.objects().orEmpty()
        fun original(type: String) = images.filter { it.optString("type") == type }
            .sortedByDescending { it.optBoolean("main") }
            .firstNotNullOfOrNull { image ->
                image.optJSONObject("resolutions")?.optJSONObject("original")
                    ?.takeIf { it.optString("url").isNotBlank() }
            }

        val background = original("background")?.optString("url")

        original("poster")?.let { poster ->
            val width = poster.optInt("width")
            val height = poster.optInt("height")
            val aspect = if (width > 0 && height > 0) width.toFloat() / height else POSTER_ASPECT
            return TvShowLogo(poster.optString("url"), TvShowLogo.Kind.POSTER, aspect, background)
        }
        return json.optJSONObject("image")?.optString("original")?.takeIf { it.isNotBlank() }
            ?.let { TvShowLogo(it, TvShowLogo.Kind.POSTER, POSTER_ASPECT, background) }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    companion object {
        private const val RETRY_MISS_MS = 30 * 60 * 1000L
        private const val RETRY_FAILED_MS = 60 * 1000L
        // TVmaze's posters are 680 x 1000, for when it doesn't say.
        private const val POSTER_ASPECT = 0.68f
    }
}
