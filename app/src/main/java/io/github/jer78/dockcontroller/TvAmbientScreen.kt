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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

// The TV screensaver: what's on, fading to a new place on the screen every
// 10 seconds (so nothing burns in). With the show's cast known (see
// TvShowCast), it takes turns with them: after a couple of moves, up to
// CAST_PAGES_PER_TURN pages of CAST_PER_PAGE people fade in, each for
// CAST_PAGE_MS - the next ones on the following turn - then back to what's on.
@Composable
fun TvAmbientScreen(
    trackState: TrackState,
    onWakeUp: () -> Unit
) {
    // Only ever shown in TV mode, so a missing artwork always falls back.
    val artworkUrl = TvFallbackArt.rememberArtworkUrl(isTvMode = true, albumArtUrl = trackState.albumArtUrl)
    var alignment by remember { mutableStateOf(Alignment.Center) }
    var visible by remember { mutableStateOf(true) }
    var castPage by remember { mutableStateOf<List<CastMember>?>(null) }
    val currentCast by rememberUpdatedState(trackState.tvCast)

    LaunchedEffect(Unit) {
        val alignments = listOf(
            Alignment.TopStart, Alignment.TopCenter, Alignment.TopEnd,
            Alignment.CenterStart, Alignment.Center, Alignment.CenterEnd,
            Alignment.BottomStart, Alignment.BottomCenter, Alignment.BottomEnd
        )
        // A row of cards is wide, so it only moves up and down.
        val castAlignments = listOf(Alignment.TopCenter, Alignment.Center, Alignment.BottomCenter)
        var nextPage = 0
        suspend fun fadeTo(move: () -> Unit) {
            visible = false
            delay(2000)
            move()
            visible = true
        }
        while (true) {
            repeat(2) {
                delay(10_000)
                fadeTo { alignment = alignments.random() }
            }
            delay(10_000)
            val pages = currentCast.chunked(CAST_PER_PAGE)
            if (pages.isNotEmpty()) {
                repeat(minOf(CAST_PAGES_PER_TURN, pages.size)) {
                    val page = pages[nextPage % pages.size]
                    nextPage++
                    fadeTo {
                        castPage = page
                        alignment = castAlignments.random()
                    }
                    delay(CAST_PAGE_MS)
                }
                fadeTo {
                    castPage = null
                    alignment = alignments.random()
                }
            }
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
            val page = castPage
            if (page != null) {
                CastPage(
                    show = detectTvShowName(trackState.title, trackState.seriesTitle, trackState.artist, trackState.album)
                        ?: trackState.title,
                    cast = page
                )
                return@AnimatedVisibility
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(60.dp)
            ) {
                // With "Replace TV Show Images" on, the show's poster (TVmaze,
                // or TMDB's alongside its logo) in its own shape, else the
                // TMDB logo on its own, else HA's artwork.
                val showLogo = trackState.tvShowLogo
                val poster = showLogo?.screensaverPoster
                if (poster != null) {
                    val (posterUrl, aspect) = poster
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(20.dp),
                        // Room left for the title and progress bar on a short screen.
                        modifier = Modifier
                            .height(minOf(380, LocalConfiguration.current.screenHeightDp / 2).dp)
                            .aspectRatio(aspect)
                    ) {
                        AsyncImage(
                            model = posterUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                } else if (showLogo != null) {
                    AsyncImage(
                        model = showLogo.url,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.width(420.dp).height(200.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                } else if (artworkUrl.isNotBlank()) {
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
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = trackState.artist,
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 22.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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

// One page of the show's cast (TV screensaver, and TV mode's Now Playing): a
// heading, then a card for each person - their photo, name, and who they
// play - rising into place one after another.
@Composable
fun CastPage(show: String, cast: List<CastMember>) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 48.dp, vertical = 60.dp)
    ) {
        Text(
            text = show.uppercase(),
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 3.sp
        )
        Text(
            text = "Starring",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            cast.forEachIndexed { index, member ->
                CastCard(member, index)
            }
        }
    }
}

@Composable
private fun CastCard(member: CastMember, index: Int) {
    val entrance = remember(member) { Animatable(0f) }
    LaunchedEffect(member) {
        delay(400L + index * 180L)
        entrance.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(170.dp)
            .graphicsLayer {
                alpha = entrance.value
                translationY = (1f - entrance.value) * 60.dp.toPx()
            }
    ) {
        Card(
            shape = RoundedCornerShape(14.dp),
            elevation = CardDefaults.cardElevation(16.dp),
            modifier = Modifier.width(170.dp).aspectRatio(0.71f)
        ) {
            AsyncImage(
                model = member.imageUrl,
                contentDescription = member.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.06f))
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            member.name,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        val role = when {
            member.guest && member.character.isNotBlank() -> "Guest star · ${member.character}"
            member.guest -> "Guest star"
            member.character.isNotBlank() -> "as ${member.character}"
            else -> ""
        }
        if (role.isNotBlank()) {
            Text(
                role,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// The cast shown at a time, how many pages each turn, and how long each stays.
const val CAST_PER_PAGE = 5
const val CAST_PAGES_PER_TURN = 2
const val CAST_PAGE_MS = 12_000L
// How often TV mode's Now Playing, left untouched, gives the cast a turn
// until the TV screensaver takes over.
const val CAST_TURN_INTERVAL_MS = 60_000L
