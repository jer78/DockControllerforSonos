package io.github.jer78.dockcontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeThumbnailsTest {
    @Test
    fun detectsYouTubeByAppNameOrId() {
        assertTrue(YouTubeThumbnails.isYouTube(TrackState(appName = "YouTube")))
        assertTrue(YouTubeThumbnails.isYouTube(TrackState(appId = "com.google.ios.youtube")))
        assertTrue(YouTubeThumbnails.isYouTube(TrackState(appId = "233637DE")))
        assertFalse(YouTubeThumbnails.isYouTube(TrackState(appName = "Netflix", appId = "com.netflix.Netflix")))
    }

    @Test
    fun readsVideoIdBareOrFromLink() {
        assertEquals("dQw4w9WgXcQ", YouTubeThumbnails.videoIdFrom("dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTubeThumbnails.videoIdFrom("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10"))
        assertEquals("dQw4w9WgXcQ", YouTubeThumbnails.videoIdFrom("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTubeThumbnails.videoIdFrom("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertNull(YouTubeThumbnails.videoIdFrom(""))
        assertNull(YouTubeThumbnails.videoIdFrom("Roadster Event"))
    }
}
