package io.github.jer78.dockcontroller

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.FlipToFront
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// Track lists for favorites, for the back of their covers in Cover Flow and
// the grid. Apple Music albums are looked up in Apple's catalog (the same
// free iTunes lookup as their genres) before they've ever played; any album
// or playlist is saved from the Sonos queue once it has played. The queue's
// list wins, being what Sonos actually plays. Saved on the device by
// favoriteKey.
object AlbumTracks {
    private class Entry(val tracks: List<QueueTrack>, val fromQueue: Boolean)

    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    private val entries = ConcurrentHashMap<String, Entry>()
    // Lookups that found nothing (or failed), not tried again for a while.
    private val misses = ConcurrentHashMap<String, Long>()
    private var file: File? = null
    @Volatile private var loaded = false

    fun init(context: Context) {
        if (file == null) file = File(context.filesDir, "album_tracks.json")
    }

    // Null when the list isn't known (and can't be looked up right now).
    suspend fun tracksFor(album: AlbumHistoryItem): List<QueueTrack>? = withContext(Dispatchers.IO) {
        ensureLoaded()
        val key = favoriteKey(album)
        entries[key]?.let { return@withContext it.tracks }
        val appleId = appleAlbumId(album) ?: return@withContext null
        misses[key]?.let { if (System.currentTimeMillis() - it < RETRY_MS) return@withContext null }
        val tracks = lookUpApple(appleId)
        if (tracks.isNullOrEmpty()) {
            misses[key] = System.currentTimeMillis()
            return@withContext null
        }
        entries[key] = Entry(tracks, fromQueue = false)
        save()
        tracks
    }

    // From the Sonos queue while the favorite plays.
    fun saveFromQueue(album: AlbumHistoryItem, tracks: List<QueueTrack>) {
        if (tracks.isEmpty()) return
        ensureLoaded()
        val key = favoriteKey(album)
        val old = entries[key]
        if (old != null && old.fromQueue && old.tracks == tracks) return
        entries[key] = Entry(tracks, fromQueue = true)
        save()
    }

    fun hasQueueList(album: AlbumHistoryItem): Boolean {
        ensureLoaded()
        return entries[favoriteKey(album)]?.fromQueue == true
    }

    // Apple Music's catalog ID, from the album's service ID
    // ("1004206calbum:1440935467") - for Apple Music favorites only (sid=204).
    private fun appleAlbumId(album: AlbumHistoryItem): String? {
        if (album.kind.isNotBlank() && album.kind != "album") return null
        if ("sid=204" !in album.playUri && "sid=204" !in album.replay) return null
        return Regex("""album:(\d+)$""").find(album.albumKey)?.groupValues?.get(1)
    }

    // The album's songs in disc and track order, from this device's country
    // store first (an album can be missing from another country's). Null if
    // Apple couldn't be reached.
    private fun lookUpApple(albumId: String): List<QueueTrack>? {
        val country = Locale.getDefault().country.takeIf { it.length == 2 }
        val first = fetchApple(albumId, country) ?: return null
        return if (first.isEmpty() && country != null) fetchApple(albumId, null) else first
    }

    private fun fetchApple(albumId: String, country: String?): List<QueueTrack>? {
        val url = "https://itunes.apple.com/lookup".toHttpUrl().newBuilder()
            .addQueryParameter("id", albumId)
            .addQueryParameter("entity", "song")
            .apply { if (country != null) addQueryParameter("country", country) }
            .build()
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val results = JSONObject(response.body?.string().orEmpty()).optJSONArray("results") ?: return emptyList()
                (0 until results.length()).mapNotNull { results.optJSONObject(it) }
                    .filter { it.optString("wrapperType") == "track" && it.optString("kind") == "song" }
                    .sortedWith(compareBy({ it.optInt("discNumber", 1) }, { it.optInt("trackNumber") }))
                    .map {
                        QueueTrack(
                            title = it.optString("trackName"),
                            artist = it.optString("artistName"),
                            duration = shortDuration(it.optLong("trackTimeMillis")),
                            album = it.optString("collectionName")
                        )
                    }
            }
        } catch (e: Exception) {
            DebugLog.w("AlbumTracks", "Apple lookup failed: ${e.message}")
            null
        }
    }

    private fun shortDuration(millis: Long): String {
        if (millis <= 0) return ""
        val seconds = millis / 1000
        return if (seconds >= 3600) String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
    }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val f = file ?: return
        try {
            if (!f.exists()) return
            val json = JSONObject(f.readText())
            json.keys().forEach { key ->
                val o = json.getJSONObject(key)
                val array = o.getJSONArray("tracks")
                val tracks = (0 until array.length()).map { i ->
                    val t = array.getJSONObject(i)
                    QueueTrack(t.optString("title"), t.optString("artist"), t.optString("duration"), t.optString("album"))
                }
                entries[key] = Entry(tracks, o.optBoolean("queue"))
            }
        } catch (e: Exception) {
            DebugLog.e("AlbumTracks", "Couldn't read saved track lists", e)
        }
    }

    @Synchronized
    private fun save() {
        val f = file ?: return
        try {
            val json = JSONObject()
            entries.forEach { (key, entry) ->
                val array = JSONArray()
                entry.tracks.forEach {
                    array.put(JSONObject().put("title", it.title).put("artist", it.artist).put("duration", it.duration).put("album", it.album))
                }
                json.put(key, JSONObject().put("queue", entry.fromQueue).put("tracks", array))
            }
            // Written to a temp file first, like the saved favorites.
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (e: Exception) {
            DebugLog.e("AlbumTracks", "Couldn't save track lists", e)
        }
    }

    private const val RETRY_MS = 30 * 60 * 1000L
}

// The Tracks button beside a cover's X, in the same style.
@Composable
fun AlbumTracksButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)) {
        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Track list", tint = Color.White)
    }
}

// The back of a cover, flipped round by its Tracks button: the cover heavily
// blurred and darkened, with the album's songs over it. Tapping one plays
// the album from that song; the top-right button turns it back over.
// onTouch hears every touch, to keep it open a while longer.
@Composable
fun AlbumTracksBack(
    album: AlbumHistoryItem,
    tracks: List<QueueTrack>,
    onPlayTrack: (QueueTrack) -> Unit,
    onFlipBack: () -> Unit,
    modifier: Modifier = Modifier,
    onTouch: () -> Unit = {}
) {
    val currentOnTouch by rememberUpdatedState(onTouch)
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) currentOnTouch()
                    }
                }
            }
    ) {
        AsyncImage(
            model = album.artUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(32.dp)
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
        Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    Text(album.albumTitle, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (album.artist.isNotBlank()) {
                        Text(album.artist, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = onFlipBack, modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)) {
                    Icon(Icons.Default.FlipToFront, contentDescription = "Back to the cover", tint = Color.White)
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f).padding(end = 10.dp)) {
                itemsIndexed(tracks) { index, track ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlayTrack(track) }
                            .padding(vertical = 9.dp)
                    ) {
                        Text("${index + 1}", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp, modifier = Modifier.width(30.dp))
                        Text(
                            track.title,
                            color = Color.White,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (track.duration.isNotBlank()) {
                            Spacer(Modifier.width(10.dp))
                            Text(track.duration, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
