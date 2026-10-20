package io.github.jer78.dockcontroller

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// Someone in the cast of the TV show on, for the TV screensaver: who they
// are, who they play, and their headshot (a downloaded copy once there is
// one). guest: a guest star of the episode playing, not the regular cast.
data class CastMember(
    val name: String,
    val character: String,
    val imageUrl: String,
    val guest: Boolean = false
)

// A show's saved cast, as Settings > Home Assistant > Saved TV Cast lists it:
// one entry for everyone saved for the show (regular cast and every episode's
// guest stars), with a few of their headshots.
data class SavedShowCast(
    val key: String,
    val name: String,
    val people: Int,
    val previewImageUrls: List<String>,
    val lastUsedMs: Long
)

// The cast of the TV show on, plus the playing episode's guest stars when the
// title says which season and episode it is. With the user's TMDB key (the
// one for show logos), from TMDB: the cast of the season that's on, when the
// season is known. Otherwise - or when TMDB doesn't know the show - from
// TVmaze (no key needed), which only has the show's current cast. Saved on
// the device with the headshots downloaded, like the shows' artwork in
// TvArtworkStore: looked up again after REFRESH_MS, so cast changes show up,
// and deleted once not shown for UNUSED_DELETE_MS.
class TvShowCast private constructor(context: Context) {
    // showId: TMDB's or TVmaze's, as fromTmdb says. withTmdbKey: a key was
    // set when it was looked up (so adding one later looks it up again).
    // name: the show as it was found, for Settings.
    private class Saved(
        val cast: List<CastMember>,
        val showId: Int,
        val savedAtMs: Long,
        val lastUsedMs: Long,
        val name: String = "",
        val fromTmdb: Boolean = false,
        val withTmdbKey: Boolean = false
    ) {
        fun used(atMs: Long) = Saved(cast, showId, savedAtMs, atMs, name, fromTmdb, withTmdbKey)
    }

    private val _shows = MutableStateFlow<List<SavedShowCast>>(emptyList())
    // Every show with a saved cast, most recently shown first.
    val shows: StateFlow<List<SavedShowCast>> = _shows.asStateFlow()

    private val dir = File(context.applicationContext.filesDir, "tv_cast")
    private val file = File(context.applicationContext.filesDir, "tv_cast.json")
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    // "show:<name>" (the show's current cast), "season:<name>|<season>" (a
    // season's cast, from TMDB) and "episode:<name>|<season>|<episode>"
    // (that episode's guest stars).
    private val saved = ConcurrentHashMap<String, Saved>()
    private val misses = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    init {
        load()
        cleanUp()
        publish()
    }

    private fun showKey(show: String) = "show:${show.lowercase()}"
    private fun seasonKey(show: String, season: Int) = "season:${show.lowercase()}|$season"
    private fun episodeKey(show: String, season: Int, episode: Int) = "episode:${show.lowercase()}|$season|$episode"

    // Where the regular cast is kept: a season's own with a TMDB key and a
    // known season, else the show's current one.
    private fun regularKey(show: String, seasonEpisode: Pair<Int, Int>?, tmdbKey: String) =
        if (tmdbKey.isNotBlank() && seasonEpisode != null) seasonKey(show, seasonEpisode.first) else showKey(show)

    private fun lookupKey(show: String, seasonEpisode: Pair<Int, Int>?, tmdbKey: String) =
        seasonEpisode?.let { episodeKey(show, it.first, it.second) } ?: regularKey(show, null, tmdbKey)

    // A saved regular cast still good to show: not due a refresh, and found
    // with a TMDB key if there's one now.
    private fun current(entry: Saved?, tmdbKey: String): Saved? =
        entry?.takeIf { System.currentTimeMillis() - it.savedAtMs <= REFRESH_MS && (tmdbKey.isBlank() || it.withTmdbKey) }

    // The regular cast, then this episode's guest stars, with the downloaded
    // headshots - null until looked up (see claimLookup/lookUp).
    fun cached(show: String, seasonEpisode: Pair<Int, Int>?, tmdbKey: String): List<CastMember>? {
        val key = regularKey(show, seasonEpisode, tmdbKey)
        val regular = current(saved[key], tmdbKey) ?: return null
        val guests = if (seasonEpisode != null) {
            (saved[episodeKey(show, seasonEpisode.first, seasonEpisode.second)] ?: return null).cast
        } else emptyList()
        markUsed(key)
        val names = regular.cast.map { it.name }.toSet()
        return (regular.cast + guests.filter { it.name !in names }).map { it.copy(imageUrl = imageUrl(it.imageUrl)) }
    }

    // True when a lookup should start now; callers then call lookUp().
    fun claimLookup(show: String, seasonEpisode: Pair<Int, Int>?, tmdbKey: String): Boolean {
        val key = lookupKey(show, seasonEpisode, tmdbKey)
        misses[key]?.let { if (System.currentTimeMillis() - it < RETRY_MS) return false }
        return inFlight.add(key)
    }

    suspend fun lookUp(show: String, seasonEpisode: Pair<Int, Int>?, tmdbKey: String): List<CastMember>? = withContext(Dispatchers.IO) {
        val key = lookupKey(show, seasonEpisode, tmdbKey)
        val regularKey = regularKey(show, seasonEpisode, tmdbKey)
        try {
            val regular = current(saved[regularKey], tmdbKey)
                ?: fetchRegular(show, seasonEpisode?.first, tmdbKey)?.also { save(regularKey, it) }
            if (regular == null) {
                misses[key] = System.currentTimeMillis()
                return@withContext null
            }
            if (seasonEpisode != null && saved[episodeKey(show, seasonEpisode.first, seasonEpisode.second)] == null) {
                // Saved even when there are none, so it isn't asked again.
                val guests = (if (regular.fromTmdb) fetchTmdbGuests(regular.showId, seasonEpisode, tmdbKey) else fetchGuests(regular.showId, seasonEpisode))
                    ?: emptyList()
                val now = System.currentTimeMillis()
                save(
                    episodeKey(show, seasonEpisode.first, seasonEpisode.second),
                    Saved(guests, regular.showId, now, now, fromTmdb = regular.fromTmdb, withTmdbKey = regular.withTmdbKey)
                )
            }
            misses.remove(key)
            cached(show, seasonEpisode, tmdbKey)
        } finally {
            inFlight.remove(key)
        }
    }

    // TMDB first when there's a key (the season's cast if it's known), then
    // TVmaze - marked as found with the key either way, so a show TMDB
    // doesn't know isn't asked about on every refresh.
    private fun fetchRegular(show: String, season: Int?, tmdbKey: String): Saved? {
        val withKey = tmdbKey.isNotBlank()
        val found = (if (withKey) fetchTmdbCast(show, season, tmdbKey) else null) ?: fetchShowCast(show) ?: return null
        return Saved(found.cast, found.showId, found.savedAtMs, found.lastUsedMs, show, found.fromTmdb, withKey)
    }

    // TMDB's cast: of the given season, else the show's current one - up to
    // MAX_REGULAR, in TMDB's billing order.
    private fun fetchTmdbCast(show: String, season: Int?, tmdbKey: String): Saved? {
        val results = tmdbGet("/search/tv", mapOf("query" to show, "include_adult" to "false"), tmdbKey)
            ?.optJSONArray("results") ?: return null
        val id = results.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 } ?: return null
        val path = if (season != null) "/tv/$id/season/$season/credits" else "/tv/$id/credits"
        val credits = tmdbGet(path, emptyMap(), tmdbKey) ?: return null
        val cast = tmdbMembers(credits.optJSONArray("cast"), guest = false).take(MAX_REGULAR)
        if (cast.isEmpty()) return null
        cast.forEach { download(it.imageUrl) }
        val now = System.currentTimeMillis()
        return Saved(cast, id, now, now, fromTmdb = true)
    }

    private fun fetchTmdbGuests(showId: Int, seasonEpisode: Pair<Int, Int>, tmdbKey: String): List<CastMember>? {
        val credits = tmdbGet("/tv/$showId/season/${seasonEpisode.first}/episode/${seasonEpisode.second}/credits", emptyMap(), tmdbKey)
            ?: return null
        val guests = tmdbMembers(credits.optJSONArray("guest_stars"), guest = true).take(MAX_GUESTS)
        guests.forEach { download(it.imageUrl) }
        return guests
    }

    // Only people with a profile photo, as for TVmaze.
    private fun tmdbMembers(array: JSONArray?, guest: Boolean): List<CastMember> =
        (0 until (array?.length() ?: 0)).mapNotNull { i ->
            val person = array?.optJSONObject(i) ?: return@mapNotNull null
            val photo = person.optString("profile_path").takeIf { it.startsWith("/") } ?: return@mapNotNull null
            CastMember(
                name = person.optString("name"),
                character = person.optString("character"),
                imageUrl = "https://image.tmdb.org/t/p/w185$photo",
                guest = guest
            )
        }.distinctBy { it.name }

    // TMDB takes either a v3 API key (as ?api_key=) or a v4 read access
    // token (a long "eyJ..." JWT, as a Bearer header), as for show logos.
    private fun tmdbGet(path: String, params: Map<String, String>, tmdbKey: String): JSONObject? {
        val isToken = tmdbKey.startsWith("eyJ")
        val url = "https://api.themoviedb.org/3$path".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            if (!isToken) addQueryParameter("api_key", tmdbKey.trim())
        }.build()
        return try {
            client.newCall(
                Request.Builder().url(url).apply { if (isToken) addHeader("Authorization", "Bearer ${tmdbKey.trim()}") }.build()
            ).execute().use { response ->
                if (!response.isSuccessful) null else JSONObject(response.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            DebugLog.w("TvShowCast", "TMDB lookup failed: ${e.message}")
            null
        }
    }

    // The show's regular cast from TVmaze (up to MAX_REGULAR, as TVmaze
    // orders them).
    private fun fetchShowCast(show: String): Saved? {
        val url = "https://api.tvmaze.com/singlesearch/shows".toHttpUrl().newBuilder()
            .addQueryParameter("q", show)
            .addQueryParameter("embed", "cast")
            .build()
        val json = getJson(url.toString()) ?: return null
        val cast = members(json.optJSONObject("_embedded")?.optJSONArray("cast"), guest = false).take(MAX_REGULAR)
        cast.forEach { download(it.imageUrl) }
        val now = System.currentTimeMillis()
        return Saved(cast, json.optInt("id"), now, now)
    }

    private fun fetchGuests(showId: Int, seasonEpisode: Pair<Int, Int>): List<CastMember>? {
        val url = "https://api.tvmaze.com/shows/$showId/episodebynumber".toHttpUrl().newBuilder()
            .addQueryParameter("season", seasonEpisode.first.toString())
            .addQueryParameter("number", seasonEpisode.second.toString())
            .addQueryParameter("embed", "guestcast")
            .build()
        val json = getJson(url.toString()) ?: return null
        val guests = members(json.optJSONObject("_embedded")?.optJSONArray("guestcast"), guest = true).take(MAX_GUESTS)
        guests.forEach { download(it.imageUrl) }
        return guests
    }

    // Only people with a picture - a blank card adds nothing on screen. The
    // actor's headshot, else the character's.
    private fun members(array: JSONArray?, guest: Boolean): List<CastMember> =
        (0 until (array?.length() ?: 0)).mapNotNull { i ->
            val entry = array?.optJSONObject(i) ?: return@mapNotNull null
            val person = entry.optJSONObject("person") ?: return@mapNotNull null
            val character = entry.optJSONObject("character")
            val image = person.optJSONObject("image")?.optString("medium")?.ifBlank { null }
                ?: character?.optJSONObject("image")?.optString("medium")?.ifBlank { null }
                ?: return@mapNotNull null
            CastMember(
                name = person.optString("name"),
                character = character?.optString("name").orEmpty(),
                imageUrl = image.replace("http://", "https://"),
                guest = guest
            )
        }.distinctBy { it.name }

    private fun getJson(url: String): JSONObject? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) null else JSONObject(response.body?.string().orEmpty())
        }
    } catch (e: Exception) {
        DebugLog.w("TvShowCast", "Lookup failed: ${e.message}")
        null
    }

    private fun markUsed(key: String) {
        val entry = saved[key] ?: return
        val now = System.currentTimeMillis()
        if (now - entry.lastUsedMs > USE_STAMP_INTERVAL_MS) {
            saved[key] = entry.used(now)
            persist()
        }
    }

    private fun save(key: String, entry: Saved) {
        saved[key] = entry
        persist()
    }

    // A show's whole saved cast - regular and every episode's guests - and
    // the headshots nothing else uses. Looked up again next time it's on.
    fun deleteShow(key: String) {
        val show = key.removePrefix("show:")
        saved.keys.filter { showOf(it) == show }.forEach { saved.remove(it) }
        misses.clear()
        persist()
        deleteUnusedImages()
    }

    fun deleteAll() {
        saved.clear()
        misses.clear()
        persist()
        dir.listFiles()?.forEach { it.delete() }
    }

    // Space the downloaded headshots take, in bytes.
    fun sizeOnDisk(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun deleteUnusedImages() {
        val inUse = saved.values.flatMap { it.cast }.map { imageFile(it.imageUrl).name }.toSet()
        dir.listFiles()?.filter { it.name !in inUse }?.forEach { it.delete() }
    }

    // The (lowercased) show any saved key belongs to.
    private fun showOf(key: String): String = key.substringAfter(':').substringBefore('|')

    // One entry per show for Settings, with all its seasons' casts and
    // episodes' guests counted in.
    private fun publish() {
        _shows.value = saved.entries.groupBy { showOf(it.key) }.map { (show, entries) ->
            val everyone = entries.sortedBy { if (it.key.startsWith("episode:")) 1 else 0 }
                .flatMap { it.value.cast }.distinctBy { it.name }
            SavedShowCast(
                key = "show:$show",
                name = entries.firstNotNullOfOrNull { it.value.name.ifBlank { null } } ?: show,
                people = everyone.size,
                previewImageUrls = everyone.take(3).map { imageUrl(it.imageUrl) },
                lastUsedMs = entries.maxOf { it.value.lastUsedMs }
            )
        }.sortedByDescending { it.lastUsedMs }
    }

    // Named after the URL, so the same headshot is only stored once.
    private fun imageFile(url: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
        return File(dir, hash)
    }

    private fun imageUrl(url: String): String = imageFile(url).takeIf { it.exists() }?.toURI()?.toString() ?: url

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
            DebugLog.w("TvShowCast", "Couldn't download a headshot: ${e.message}")
        }
    }

    // Shows (and episodes) not shown for UNUSED_DELETE_MS, and headshots
    // nothing uses any more.
    @Synchronized
    private fun cleanUp() {
        val now = System.currentTimeMillis()
        // A show's seasons and episodes count as shown while any of it is.
        val showLastUsed = saved.entries.groupBy { showOf(it.key) }.mapValues { (_, entries) -> entries.maxOf { it.value.lastUsedMs } }
        val stale = saved.keys.filter { now - (showLastUsed[showOf(it)] ?: 0L) > UNUSED_DELETE_MS }
        if (stale.isNotEmpty()) {
            stale.forEach { saved.remove(it) }
            persist()
        }
        val inUse = saved.values.flatMap { it.cast }.map { imageFile(it.imageUrl).name }.toSet()
        dir.listFiles()
            ?.filter { it.name !in inUse && now - it.lastModified() > USE_STAMP_INTERVAL_MS }
            ?.forEach { it.delete() }
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            json.keys().forEach { key ->
                val o = json.getJSONObject(key)
                val array = o.getJSONArray("cast")
                val cast = (0 until array.length()).map { i ->
                    val c = array.getJSONObject(i)
                    CastMember(c.optString("name"), c.optString("character"), c.optString("image"), c.optBoolean("guest"))
                }
                saved[key] = Saved(
                    cast, o.optInt("show_id"), o.optLong("saved_at"), o.optLong("last_used", o.optLong("saved_at")),
                    o.optString("name"), o.optBoolean("from_tmdb"), o.optBoolean("with_tmdb_key")
                )
            }
        } catch (e: Exception) {
            DebugLog.e("TvShowCast", "Couldn't read saved cast", e)
        }
    }

    @Synchronized
    private fun persist() {
        try {
            val json = JSONObject()
            saved.forEach { (key, entry) ->
                val array = JSONArray()
                entry.cast.forEach {
                    array.put(JSONObject().put("name", it.name).put("character", it.character).put("image", it.imageUrl).put("guest", it.guest))
                }
                json.put(
                    key,
                    JSONObject().put("cast", array).put("show_id", entry.showId).put("name", entry.name)
                        .put("from_tmdb", entry.fromTmdb).put("with_tmdb_key", entry.withTmdbKey)
                        .put("saved_at", entry.savedAtMs).put("last_used", entry.lastUsedMs)
                )
            }
            // Written to a temp file first, like the saved TV artwork.
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            DebugLog.e("TvShowCast", "Couldn't save cast", e)
        }
        // Every change is saved, so Settings' list follows from here.
        publish()
    }

    companion object {
        private const val MAX_REGULAR = 12
        private const val MAX_GUESTS = 6
        private const val REFRESH_MS = 7L * 24 * 60 * 60 * 1000
        private const val RETRY_MS = 30L * 60 * 1000
        private const val UNUSED_DELETE_MS = 14L * 24 * 60 * 60 * 1000
        private const val USE_STAMP_INTERVAL_MS = 60L * 60 * 1000

        @Volatile
        private var instance: TvShowCast? = null

        fun getInstance(context: Context): TvShowCast =
            instance ?: synchronized(this) {
                instance ?: TvShowCast(context).also { instance = it }
            }
    }
}
