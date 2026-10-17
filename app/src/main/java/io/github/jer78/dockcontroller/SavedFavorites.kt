package io.github.jer78.dockcontroller

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Every favorite the app has ever seen on Sonos, kept after it's removed
// from Sonos Favorites - so Cover Flow can hold more than Sonos's ~70. A
// refresh only adds and updates; anything Sonos no longer has is marked
// onSonos = false and played from its saved replay data instead.

// playUri prefix for a favorite that's no longer on Sonos. Its old FV:2/<n>
// ID can't be kept: Sonos reuses those, so it could delete or play the
// wrong favorite.
const val LOCAL_FAVORITE_PREFIX = "local:"

// What identifies a favorite across refreshes. FV:2/<n> IDs shift whenever
// favorites change, so this goes by the service's own ID, else the title.
fun favoriteKey(item: AlbumHistoryItem): String =
    item.albumKey.takeIf { it.isNotBlank() && !it.startsWith("FV:") }?.let { "id:$it" }
        ?: "title:${item.kind}:${item.albumTitle.trim().lowercase()}"

fun AlbumHistoryItem.isSavedLocally(): Boolean = playUri.startsWith(LOCAL_FAVORITE_PREFIX)

fun AlbumHistoryItem.asLocalOnly(): AlbumHistoryItem =
    copy(onSonos = false, playUri = LOCAL_FAVORITE_PREFIX + favoriteKey(this))

// The saved list after a successful refresh found fromSonos: Sonos's own
// details win, anything missing from Sonos stays as local-only.
fun mergeSavedFavorites(saved: List<AlbumHistoryItem>, fromSonos: List<AlbumHistoryItem>): List<AlbumHistoryItem> {
    val savedByKey = saved.associateBy { favoriteKey(it) }
    val sonos = fromSonos.distinctBy { favoriteKey(it) }.map { item ->
        val old = savedByKey[favoriteKey(item)]
        item.copy(
            onSonos = true,
            replay = item.replay.ifBlank { old?.replay.orEmpty() },
            genre = item.genre.ifBlank { old?.genre.orEmpty() }
        )
    }
    val sonosKeys = sonos.map { favoriteKey(it) }.toSet()
    val localOnly = saved.filter { favoriteKey(it) !in sonosKeys }
        .map { if (it.isSavedLocally()) it else it.asLocalOnly() }
    return sonos + localOnly
}

class SavedFavoritesStore(context: Context) {
    private val file = File(context.filesDir, "saved_favorites.json")

    fun load(): List<AlbumHistoryItem> = try {
        if (!file.exists()) emptyList() else {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                AlbumHistoryItem(
                    artUrl = o.optString("art"),
                    artist = o.optString("artist"),
                    albumTitle = o.optString("title"),
                    playUri = o.optString("uri"),
                    playMetadata = o.optString("meta"),
                    kind = o.optString("kind"),
                    albumKey = o.optString("album_key"),
                    genre = o.optString("genre"),
                    onSonos = o.optBoolean("on_sonos", true),
                    replay = o.optString("replay")
                )
            }
        }
    } catch (e: Exception) {
        Log.e("SavedFavorites", "Couldn't read saved favorites", e)
        emptyList()
    }

    fun save(items: List<AlbumHistoryItem>) {
        try {
            val array = JSONArray()
            items.forEach {
                array.put(
                    JSONObject()
                        .put("art", it.artUrl)
                        .put("artist", it.artist)
                        .put("title", it.albumTitle)
                        .put("uri", it.playUri)
                        .put("meta", it.playMetadata)
                        .put("kind", it.kind)
                        .put("album_key", it.albumKey)
                        .put("genre", it.genre)
                        .put("on_sonos", it.onSonos)
                        .put("replay", it.replay)
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
            Log.e("SavedFavorites", "Couldn't save favorites", e)
        }
    }
}
