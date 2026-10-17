package io.github.jer78.dockcontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedFavoritesTest {
    private fun fav(title: String, fvId: String, key: String, replay: String = "r-$title") =
        AlbumHistoryItem("", "Artist", title, playUri = fvId, kind = "album", albumKey = key, replay = replay)

    @Test
    fun refreshAddsAndKeepsRemovedFavoritesAsLocalOnly() {
        val saved = listOf(fav("A", "FV:2/1", "svc:a"), fav("B", "FV:2/2", "svc:b"))
        val merged = mergeSavedFavorites(saved, listOf(fav("B", "FV:2/1", "svc:b"), fav("C", "FV:2/2", "svc:c")))

        assertEquals(3, merged.size)
        val a = merged.single { it.albumTitle == "A" }
        assertFalse(a.onSonos)
        // Never the old FV:2 ID - Sonos reuses those.
        assertEquals("local:id:svc:a", a.playUri)
        assertEquals("r-A", a.replay)
        val b = merged.single { it.albumTitle == "B" }
        assertTrue(b.onSonos)
        assertEquals("FV:2/1", b.playUri)
    }

    @Test
    fun localOnlyFavoriteComesBackWhenReAddedToSonos() {
        val saved = listOf(fav("A", "FV:2/1", "svc:a").asLocalOnly())
        val merged = mergeSavedFavorites(saved, listOf(fav("A", "FV:2/9", "svc:a", replay = "")))

        val a = merged.single()
        assertTrue(a.onSonos)
        assertEquals("FV:2/9", a.playUri)
        // Keeps the saved replay data when the refresh couldn't capture any.
        assertEquals("r-A", a.replay)
    }

    @Test
    fun favoritesWithoutServiceIdMatchByTitle() {
        val saved = listOf(fav("Radio X", "FV:2/3", "FV:2/3").copy(kind = "radio"))
        val merged = mergeSavedFavorites(saved, listOf(fav("Radio X", "FV:2/4", "FV:2/4").copy(kind = "radio")))
        assertEquals(1, merged.size)
        assertTrue(merged.single().onSonos)
    }
}
