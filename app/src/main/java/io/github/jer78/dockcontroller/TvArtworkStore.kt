package io.github.jer78.dockcontroller

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

// TV mode artwork the app has found - a show's TMDB logo or TVmaze poster,
// or a YouTube video's thumbnail - kept across restarts with its images
// downloaded, so each show or video is only looked up once. Settings > Home
// Assistant > Saved TV Artwork lists them; deleting one has it looked up
// again the next time it plays.
data class SavedTvArtwork(
    // "show:<name>" or "youtube:<video>" - see TvShowLogos/YouTubeThumbnails.
    val key: String,
    // The show's name, or the video's title.
    val name: String,
    val source: Source,
    // The images as found online; shown from the downloaded copies when
    // there are any (see imageUrl/backgroundImageUrl).
    val url: String,
    val backgroundUrl: String? = null,
    // Width / height of a poster, so the artwork card can take its shape.
    val aspectRatio: Float = 16f / 9f,
    // Whether TMDB was asked too (a key was set), so adding a key later
    // looks shows TVmaze answered up again.
    val withTmdbKey: Boolean = false,
    val savedAtMs: Long = System.currentTimeMillis()
) {
    enum class Source(val label: String) {
        TMDB_LOGO("TMDB logo"),
        TVMAZE_POSTER("TVmaze poster"),
        YOUTUBE("YouTube thumbnail")
    }
}

class TvArtworkStore private constructor(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "tv_artwork")
    private val file = File(context.applicationContext.filesDir, "tv_artwork.json")
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    private val _entries = MutableStateFlow(load())
    // Newest first.
    val entries: StateFlow<List<SavedTvArtwork>> = _entries.asStateFlow()

    fun get(key: String): SavedTvArtwork? = _entries.value.firstOrNull { it.key == key }

    // Downloads its images (a failed download just means it's shown from
    // the web instead) and saves it, replacing any older entry for the key.
    fun save(entry: SavedTvArtwork) {
        download(entry.url)
        entry.backgroundUrl?.let { download(it) }
        _entries.update { list -> listOf(entry) + list.filter { it.key != entry.key } }
        persist()
    }

    fun delete(key: String) {
        val removed = get(key) ?: return
        _entries.update { list -> list.filter { it.key != key } }
        persist()
        deleteImages(removed)
    }

    fun deleteAll() {
        _entries.value = emptyList()
        persist()
        dir.listFiles()?.forEach { it.delete() }
    }

    // Space the downloaded images take, in bytes.
    fun sizeOnDisk(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    // The downloaded copy as a file:// URL, or the original if it isn't
    // downloaded (yet).
    fun imageUrl(url: String): String = imageFile(url).takeIf { it.exists() }?.toURI()?.toString() ?: url

    fun logoFor(entry: SavedTvArtwork): TvShowLogo = TvShowLogo(
        url = imageUrl(entry.url),
        kind = if (entry.source == SavedTvArtwork.Source.TMDB_LOGO) TvShowLogo.Kind.TRANSPARENT_LOGO else TvShowLogo.Kind.POSTER,
        aspectRatio = entry.aspectRatio,
        backgroundUrl = entry.backgroundUrl?.let { imageUrl(it) }
    )

    // Named after the URL, so the same image is only stored once.
    private fun imageFile(url: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
        return File(dir, hash)
    }

    private fun download(url: String) {
        val target = imageFile(url)
        if (target.exists()) return
        try {
            dir.mkdirs()
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return
                val tmp = File(dir, target.name + ".tmp")
                response.body?.byteStream()?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(target)
            }
        } catch (e: Exception) {
            Log.w("TvArtworkStore", "Couldn't download ${url.substringBefore('?')}: ${e.message}")
        }
    }

    // Only images no other entry still uses.
    private fun deleteImages(removed: SavedTvArtwork) {
        val stillUsed = _entries.value.flatMap { listOfNotNull(it.url, it.backgroundUrl) }.toSet()
        listOfNotNull(removed.url, removed.backgroundUrl)
            .filter { it !in stillUsed }
            .forEach { imageFile(it).delete() }
    }

    private fun load(): List<SavedTvArtwork> = try {
        if (!file.exists()) emptyList() else {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { i ->
                val o = array.getJSONObject(i)
                val source = SavedTvArtwork.Source.entries.find { it.name == o.optString("source") } ?: return@mapNotNull null
                SavedTvArtwork(
                    key = o.optString("key"),
                    name = o.optString("name"),
                    source = source,
                    url = o.optString("url"),
                    backgroundUrl = o.optString("background").ifBlank { null },
                    aspectRatio = o.optDouble("aspect", 16.0 / 9.0).toFloat(),
                    withTmdbKey = o.optBoolean("with_tmdb_key"),
                    savedAtMs = o.optLong("saved_at")
                )
            }
        }
    } catch (e: Exception) {
        Log.e("TvArtworkStore", "Couldn't read saved TV artwork", e)
        emptyList()
    }

    @Synchronized
    private fun persist() {
        try {
            val array = JSONArray()
            _entries.value.forEach {
                array.put(
                    JSONObject()
                        .put("key", it.key)
                        .put("name", it.name)
                        .put("source", it.source.name)
                        .put("url", it.url)
                        .put("background", it.backgroundUrl ?: "")
                        .put("aspect", it.aspectRatio.toDouble())
                        .put("with_tmdb_key", it.withTmdbKey)
                        .put("saved_at", it.savedAtMs)
                )
            }
            // Written to a temp file first, so a crash mid-write can't
            // wipe the whole list.
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(array.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.e("TvArtworkStore", "Couldn't save TV artwork", e)
        }
    }

    companion object {
        @Volatile
        private var instance: TvArtworkStore? = null

        fun getInstance(context: Context): TvArtworkStore =
            instance ?: synchronized(this) {
                instance ?: TvArtworkStore(context).also { instance = it }
            }
    }
}
