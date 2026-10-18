package io.github.jer78.dockcontroller

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

// Settings > Now Playing > Artist Suggestions: once music has played on
// Cover Flow / the grid for SUGGESTIONS_AFTER_MS, and there's something to
// suggest, onShow is called with it (see ArtistSuggestions for how often).
// The lookup runs during the wait, so they're ready on time. Leaving Cover
// Flow, stopping, or a new artist starts the wait over.
@Composable
fun SuggestionsEffect(
    active: Boolean,
    artist: String,
    apiKey: String,
    favorites: List<AlbumHistoryItem>,
    onShow: (List<SuggestedAlbum>) -> Unit
) {
    val context = LocalContext.current
    val currentFavorites by rememberUpdatedState(favorites)
    val currentOnShow by rememberUpdatedState(onShow)
    LaunchedEffect(active, artist, apiKey) {
        if (!active || artist.isBlank() || apiKey.isBlank()) return@LaunchedEffect
        val earliest = System.currentTimeMillis() + SUGGESTIONS_AFTER_MS
        // The artwork is loaded during the wait too, into Coil's caches, so
        // every cover is there as the cards rise in - any that won't load
        // are left out rather than shown blank.
        val suggestions = coroutineScope {
            ArtistSuggestions.suggest(artist, apiKey, currentFavorites).map { suggestion ->
                async {
                    val request = ImageRequest.Builder(context).data(suggestion.artUrl).build()
                    suggestion.takeIf { context.imageLoader.execute(request) is SuccessResult }
                }
            }.awaitAll().filterNotNull()
        }
        if (suggestions.isEmpty()) return@LaunchedEffect
        delay(maxOf(earliest, ArtistSuggestions.nextShowAtMs(artist)) - System.currentTimeMillis())
        ArtistSuggestions.markShown(artist, suggestions)
        currentOnShow(suggestions)
    }
}

// The Sonos app's launch intent, if it's installed - the new app, else S1.
fun sonosAppLaunchIntent(context: Context): Intent? =
    listOf("com.sonos.acr2", "com.sonos.acr").firstNotNullOfOrNull {
        context.packageManager.getLaunchIntentForPackage(it)
    }

// Three albums by artists like the one playing. They can't be played from
// here (only Sonos Favorites can), so a tap on one says how to add it in the
// Sonos app. Goes back (onDone) after SUGGESTIONS_SHOW_MS untouched, or
// SUGGESTIONS_IDLE_MS after the last touch.
@Composable
fun SuggestionsScreen(
    suggestions: List<SuggestedAlbum>,
    playingArtist: String,
    onDone: () -> Unit,
    // Null when the Sonos app isn't installed.
    onOpenSonosApp: (() -> Unit)?
) {
    var touches by remember { mutableIntStateOf(0) }
    val currentOnDone by rememberUpdatedState(onDone)
    LaunchedEffect(touches) {
        delay(if (touches == 0) SUGGESTIONS_SHOW_MS else SUGGESTIONS_IDLE_MS)
        currentOnDone()
    }
    var selected by remember { mutableStateOf<SuggestedAlbum?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) touches++
                    }
                }
            }
            // A tap on the background puts the cards back as they were.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { selected = null }
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
            val cardSize = min(maxWidth * 0.27f, maxHeight * 0.5f)
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "If you like $playingArtist",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "You might also like",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    suggestions.forEachIndexed { index, suggestion ->
                        SuggestionCard(
                            suggestion = suggestion,
                            index = index,
                            size = cardSize,
                            dimmed = selected != null && selected != suggestion,
                            highlighted = selected == suggestion,
                            onClick = { selected = if (selected == suggestion) null else suggestion }
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                val pick = selected
                Text(
                    if (pick != null) {
                        "To play it, search for \"${pick.album}\" by ${pick.artist} in the Sonos app and add it to " +
                            "My Sonos. It'll show up here after the next favorites refresh." +
                            (if (onOpenSonosApp != null) " Open Sonos App copies it, ready to paste into Search." else "")
                    } else {
                        "Like one? Tap it to see how to add it in the Sonos app."
                    },
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 640.dp)
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (onOpenSonosApp != null) {
                        val context = LocalContext.current
                        Button(
                            // The Sonos app can't be opened on a search, so the
                            // picked album goes on the clipboard to paste into it.
                            onClick = {
                                if (pick != null) {
                                    val text = "${pick.album} ${pick.artist}"
                                    context.getSystemService(ClipboardManager::class.java)
                                        ?.setPrimaryClip(ClipData.newPlainText("Album", text))
                                    Toast.makeText(context, "Copied \"$text\" - paste it into Search in Sonos", Toast.LENGTH_LONG).show()
                                }
                                onOpenSonosApp()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.18f), contentColor = Color.White)
                        ) { Text("Open Sonos App") }
                    }
                    TextButton(onClick = onDone) { Text("Done", color = Color.White.copy(alpha = 0.75f)) }
                }
            }
        }
        // Last.fm's terms ask for credit where its data is shown.
        Text(
            "Suggestions from Last.fm",
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
        )
    }
}

@Composable
private fun SuggestionCard(
    suggestion: SuggestedAlbum,
    index: Int,
    size: androidx.compose.ui.unit.Dp,
    dimmed: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit
) {
    // Each card rises into place a beat after the one before.
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(300L + index * 150L)
        entrance.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
    }
    val alpha by animateFloatAsState(if (dimmed) 0.35f else 1f, tween(300), label = "suggestionDim")
    val scale by animateFloatAsState(if (highlighted) 1.06f else 1f, tween(300), label = "suggestionScale")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(size)
            .graphicsLayer {
                this.alpha = entrance.value * alpha
                translationY = (1f - entrance.value) * 80.dp.toPx()
                scaleX = scale
                scaleY = scale
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    ) {
        AsyncImage(
            model = suggestion.artUrl,
            contentDescription = "${suggestion.album} by ${suggestion.artist}",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.DarkGray)
                .then(if (highlighted) Modifier.border(2.dp, Color(0xFFFFD54F), RoundedCornerShape(8.dp)) else Modifier)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            suggestion.artist,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            suggestion.album,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alpha(0.9f)
        )
    }
}

// How long music plays on Cover Flow / the grid before suggestions show,
// how long they stay untouched, and how long after the last touch.
private const val SUGGESTIONS_AFTER_MS = 60_000L
private const val SUGGESTIONS_SHOW_MS = 45_000L
private const val SUGGESTIONS_IDLE_MS = 30_000L
