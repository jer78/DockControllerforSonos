package io.github.jer78.dockcontroller

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

@Composable
fun TvAmbientScreen(
    trackState: TrackState,
    onWakeUp: () -> Unit
) {
    // Only ever shown in TV mode, so a missing artwork always falls back.
    val artworkUrl = TvFallbackArt.rememberArtworkUrl(isTvMode = true, albumArtUrl = trackState.albumArtUrl)
    var alignment by remember { mutableStateOf(Alignment.Center) }
    var visible by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val alignments = listOf(
            Alignment.TopStart, Alignment.TopCenter, Alignment.TopEnd,
            Alignment.CenterStart, Alignment.Center, Alignment.CenterEnd,
            Alignment.BottomStart, Alignment.BottomCenter, Alignment.BottomEnd
        )
        while (true) {
            delay(10_000)
            visible = false
            delay(1000)
            alignment = alignments.random()
            visible = true
            delay(10_000)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { onWakeUp() },
        contentAlignment = alignment
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(2000)),
            exit = fadeOut(tween(2000))
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(60.dp)
            ) {
                if (artworkUrl.isNotBlank()) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(20.dp),
                        modifier = Modifier.size(300.dp)
                    ) {
                        AsyncImage(
                            model = artworkUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
                Text(
                    text = trackState.title,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = trackState.artist,
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 22.sp,
                    textAlign = TextAlign.Center
                )

                // Radio/live streams and anything the source can't time
                // report a 0 duration - skip the bar entirely rather than
                // show a stuck or meaningless one.
                if (trackState.durationSeconds > 0) {
                    val displayedSeconds = rememberInterpolatedPosition(trackState)
                    val remainingSeconds = (trackState.durationSeconds - displayedSeconds).coerceAtLeast(0)
                    Spacer(modifier = Modifier.height(20.dp))
                    LinearProgressIndicator(
                        progress = { (displayedSeconds.toFloat() / trackState.durationSeconds).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .width(280.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = Color.White.copy(alpha = 0.6f),
                        trackColor = Color.White.copy(alpha = 0.15f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "-${formatTrackTime(remainingSeconds)}",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}
