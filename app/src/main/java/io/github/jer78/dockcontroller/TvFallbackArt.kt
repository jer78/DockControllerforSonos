package io.github.jer78.dockcontroller

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

// Stand-in artwork for TV mode when the HA media_player doesn't report any
// (most TV inputs/apps don't). Any .jpg/.jpeg/.png/.webp dropped into
// app/src/main/assets/tv_fallback/ is picked up automatically - no code
// change or fixed filenames needed.
object TvFallbackArt {
    private const val ASSET_DIR = "tv_fallback"
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    private const val CYCLE_MS = 2 * 60_000L
    private const val TEST_MODE_CYCLE_MS = 5_000L

    // Debug-only (toggled from the Metadata Debugger, not persisted): forces
    // the fallback art on regardless of mode/artwork and cycles through every
    // image, so each one can be checked on the real screen. Process-wide
    // Compose state so both MainActivity and SonosDreamService see it.
    var testMode by mutableStateOf(false)

    private var cachedUrls: List<String>? = null

    fun imageUrls(context: Context): List<String> = cachedUrls ?: run {
        val names = try {
            context.assets.list(ASSET_DIR)?.toList().orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        names
            .filter { it.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
            .sorted()
            // Encoded so filenames with spaces etc. still parse as a valid URI.
            .map { "file:///android_asset/$ASSET_DIR/${Uri.encode(it)}" }
            .also { cachedUrls = it }
    }

    // Random, but never the same image twice in a row when there's a choice.
    fun pick(context: Context, previous: String? = null): String? {
        val urls = imageUrls(context)
        return (urls - setOfNotNull(previous)).randomOrNull() ?: urls.randomOrNull()
    }

    // The artwork URL to actually display: the real art when there is some,
    // otherwise a random fallback image while in TV mode, rotating to a
    // different one every couple of minutes for as long as the art stays
    // missing (every few seconds in test mode).
    @Composable
    fun rememberArtworkUrl(isTvMode: Boolean, albumArtUrl: String): String {
        val context = LocalContext.current
        val useFallback = testMode || (isTvMode && albumArtUrl.isBlank())
        var fallbackUrl by remember(useFallback) {
            mutableStateOf(if (useFallback) pick(context) else null)
        }
        LaunchedEffect(useFallback, testMode) {
            while (useFallback) {
                delay(if (testMode) TEST_MODE_CYCLE_MS else CYCLE_MS)
                fallbackUrl = pick(context, previous = fallbackUrl)
            }
        }
        return fallbackUrl ?: albumArtUrl
    }
}
