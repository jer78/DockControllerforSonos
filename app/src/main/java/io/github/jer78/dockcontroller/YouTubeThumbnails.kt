package io.github.jer78.dockcontroller

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// This device's YouTube searches today, for Settings (see
// SettingsManager.youTubeUsage). lastResult is null before the first search.
data class YouTubeUsage(
    val searchesToday: Int,
    val quotaReached: Boolean,
    val lastResult: YouTubeThumbnails.SearchResult?,
    val lastQuery: String
)

// Experimental: TV mode artwork for YouTube, which most HA integrations play
// without sending any. Google Cast reports the video ID as media_content_id,
// so the thumbnail comes straight from that; others (Apple TV, ...) only send
// the title and channel, so the video is searched for with the user's YouTube
// Data API key. No ID and no key - nothing, and the fallback art shows.
// Each thumbnail found is saved, image and all, in TvArtworkStore, so a
// video is only looked up once, even across restarts. Misses are retried
// after a while - within a minute when the search failed to get an answer.
// onSearch hears how each search went, for Settings' usage line - the API
// itself never says how much quota is left.
class YouTubeThumbnails(
    private val store: TvArtworkStore,
    private val onSearch: (SearchResult, String) -> Unit = { _, _ -> }
) {
    enum class SearchResult { FOUND, NO_MATCH, QUOTA_EXCEEDED, KEY_REJECTED, FAILED }

    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    // A search that didn't get a real answer (timeout, no network, server
    // error), as opposed to no match or a rejected key.
    private class SearchFailed : Exception()

    private data class Miss(val atMs: Long, val retryMs: Long)
    private val misses = ConcurrentHashMap<String, Miss>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    // What identifies the video: its ID when the player reports one,
    // otherwise its title and channel. Null when there's nothing to go on.
    // Adding a key looks title-only videos up again.
    private fun cacheKey(track: TrackState, apiKey: String): String? {
        videoIdFrom(track.contentId)?.let { return "id:$it" }
        if (apiKey.isBlank() || track.title.isBlank()) return null
        return "search:${track.title.lowercase()}|${track.artist.lowercase()}"
    }

    fun cached(track: TrackState, apiKey: String): String? =
        cacheKey(track, apiKey)?.let { store.get("youtube:$it") }?.let { store.imageUrl(it.url) }

    // True when a lookup should start now; callers then call lookUp().
    fun claimLookup(track: TrackState, apiKey: String): Boolean {
        val key = cacheKey(track, apiKey) ?: return false
        if (store.get("youtube:$key") != null) return false
        val miss = misses[key]
        if (miss != null && System.currentTimeMillis() - miss.atMs < miss.retryMs) return false
        return inFlight.add(key)
    }

    suspend fun lookUp(track: TrackState, apiKey: String): String? = withContext(Dispatchers.IO) {
        val key = cacheKey(track, apiKey) ?: return@withContext null
        try {
            val videoId = try {
                videoIdFrom(track.contentId) ?: search(track.title, track.artist, apiKey.trim())
            } catch (e: SearchFailed) {
                misses[key] = Miss(System.currentTimeMillis(), RETRY_FAILED_MS)
                return@withContext null
            }
            if (videoId == null) {
                misses[key] = Miss(System.currentTimeMillis(), RETRY_MISS_MS)
                return@withContext null
            }
            misses.remove(key)
            val entry = SavedTvArtwork(
                key = "youtube:$key",
                name = track.title.ifBlank { videoId },
                source = SavedTvArtwork.Source.YOUTUBE,
                url = bestThumbnail(videoId)
            )
            store.save(entry)
            store.imageUrl(entry.url)
        } finally {
            inFlight.remove(key)
        }
    }

    // The top results for "<title> <channel>", preferring one from that
    // channel - the title alone can match re-uploads and reaction videos.
    private fun search(title: String, channel: String, apiKey: String): String? {
        val url = "https://www.googleapis.com/youtube/v3/search".toHttpUrl().newBuilder()
            .addQueryParameter("part", "snippet")
            .addQueryParameter("type", "video")
            .addQueryParameter("maxResults", "5")
            .addQueryParameter("q", "$title $channel".trim())
            .addQueryParameter("key", apiKey)
            .build()
        val body = try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val text = response.body?.string()
                if (response.isSuccessful) text else {
                    DebugLog.w("YouTubeThumbnails", "Search HTTP ${response.code}")
                    val result = errorResult(response.code, text)
                    onSearch(result, title)
                    if (result == SearchResult.FAILED) throw SearchFailed()
                    return null
                }
            }
        } catch (e: SearchFailed) {
            throw e
        } catch (e: Exception) {
            DebugLog.w("YouTubeThumbnails", "Search failed: ${e.message}")
            null
        }
        if (body == null) {
            onSearch(SearchResult.FAILED, title)
            throw SearchFailed()
        }
        val items = JSONObject(body).optJSONArray("items")
        val results = items?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i) } }.orEmpty()
        val best = results.firstOrNull {
            it.optJSONObject("snippet")?.optString("channelTitle").equals(channel, ignoreCase = true)
        } ?: results.firstOrNull()
        val videoId = best?.optJSONObject("id")?.optString("videoId")?.takeIf { VIDEO_ID.matches(it) }
        onSearch(if (videoId != null) SearchResult.FOUND else SearchResult.NO_MATCH, title)
        return videoId
    }

    // Google's error body names the reason: quotaExceeded once the day's
    // quota is used up; a bad key, the API not being enabled for it, or the
    // key's restrictions blocking it all come back as 400/403 otherwise.
    private fun errorResult(code: Int, body: String?): SearchResult {
        val reasons = try {
            val errors = JSONObject(body ?: "").optJSONObject("error")?.optJSONArray("errors")
            (0 until (errors?.length() ?: 0)).map { errors!!.optJSONObject(it)?.optString("reason").orEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
        return when {
            reasons.any { it == "quotaExceeded" || it == "dailyLimitExceeded" } -> SearchResult.QUOTA_EXCEEDED
            code == 400 || code == 403 -> SearchResult.KEY_REJECTED
            else -> SearchResult.FAILED
        }
    }

    // maxresdefault is 1280x720 but missing for older and low-res videos;
    // hqdefault always exists, 4:3 with the video letterboxed inside, which
    // TV mode's 16:9 artwork crops back off.
    private fun bestThumbnail(videoId: String): String {
        val maxRes = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
        val exists = try {
            client.newCall(Request.Builder().url(maxRes).head().build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
        return if (exists) maxRes else "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }

    companion object {
        private const val RETRY_MISS_MS = 30 * 60 * 1000L
        private const val RETRY_FAILED_MS = 60 * 1000L

        // A search costs 100 of the free 10,000 daily units.
        const val FREE_SEARCHES_PER_DAY = 100

        // Google's quota day runs midnight to midnight Pacific time.
        fun quotaDay(): String = java.time.LocalDate.now(java.time.ZoneId.of("America/Los_Angeles")).toString()

        private val VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")
        // youtube.com/watch?v=ID, youtu.be/ID, youtube.com/shorts/ID, ...
        private val VIDEO_ID_IN_URL = Regex("""(?:[?&]v=|youtu\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})""")

        // Each player's own ID for its YouTube app, for when app_name isn't
        // just "YouTube": Cast's app ID, Apple TV's and Android TV's bundles.
        private val YOUTUBE_APP_IDS = setOf(
            "233637de",
            "com.google.ios.youtube",
            "com.google.android.youtube.tv",
            "com.google.android.youtube.googletv",
            "com.google.android.youtube"
        )

        fun isYouTube(track: TrackState): Boolean =
            track.appName.contains("youtube", ignoreCase = true) ||
                track.appId.lowercase() in YOUTUBE_APP_IDS

        // The video ID in media_content_id, which players send either bare
        // or as a link.
        fun videoIdFrom(contentId: String): String? {
            val id = contentId.trim()
            if (VIDEO_ID.matches(id)) return id
            return VIDEO_ID_IN_URL.find(id)?.groupValues?.get(1)
        }
    }
}
