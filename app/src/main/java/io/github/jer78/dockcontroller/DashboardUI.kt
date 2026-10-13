package io.github.jer78.dockcontroller

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import android.content.Context
import android.os.Build
import android.view.WindowManager
import java.util.function.Consumer
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// Shared, direction-aware transition for switching between the top-level
// screens (Now Playing, Cover Flow, TV Ambient) - used by both MainActivity
// and SonosDreamService so the two hosts stay in sync. Leaving Now Playing
// for a screensaver drops down (new screen enters from above, old one falls
// away below); coming back to Now Playing reverses that (old screensaver
// rises away above, Now Playing rises into place from below).
//
// Exception: Cover Flow reached by a sideways swipe on Now Playing slides in
// from the side the finger came from (and back out the same way) - `slide`
// is how the last Now Playing -> Cover Flow switch was triggered.
fun dockScreenTransitionSpec(slide: ScreenSlide): AnimatedContentTransitionScope<DockScreen>.() -> ContentTransform = {
    val spec = tween<IntOffset>(550, easing = FastOutSlowInEasing)
    val nowPlayingAndCoverFlow = setOf(initialState, targetState) == setOf(DockScreen.NOW_PLAYING, DockScreen.COVER_FLOW)
    if (nowPlayingAndCoverFlow && slide != ScreenSlide.VERTICAL) {
        // Which side Cover Flow sits off-screen: -1 left, +1 right.
        val coverFlowSide = if (slide == ScreenSlide.FROM_LEFT) -1 else 1
        if (targetState == DockScreen.COVER_FLOW) {
            slideInHorizontally(animationSpec = spec) { fullWidth -> coverFlowSide * fullWidth } togetherWith
                slideOutHorizontally(animationSpec = spec) { fullWidth -> -coverFlowSide * fullWidth }
        } else {
            slideInHorizontally(animationSpec = spec) { fullWidth -> -coverFlowSide * fullWidth } togetherWith
                slideOutHorizontally(animationSpec = spec) { fullWidth -> coverFlowSide * fullWidth }
        }
    } else if (targetState == DockScreen.NOW_PLAYING) {
        slideInVertically(animationSpec = spec) { fullHeight -> fullHeight } togetherWith
            slideOutVertically(animationSpec = spec) { fullHeight -> -fullHeight }
    } else {
        slideInVertically(animationSpec = spec) { fullHeight -> -fullHeight } togetherWith
            slideOutVertically(animationSpec = spec) { fullHeight -> fullHeight }
    }
}

fun formatClockTime(time: Calendar, use24HourClock: Boolean): String =
    if (use24HourClock) {
        String.format(Locale.getDefault(), "%02d:%02d", time.get(Calendar.HOUR_OF_DAY), time.get(Calendar.MINUTE))
    } else {
        val hour12 = time.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        val amPm = if (time.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
        String.format(Locale.getDefault(), "%d:%02d %s", hour12, time.get(Calendar.MINUTE), amPm)
    }

// Settings > Now Playing > Clock: which playback modes show the clock.
enum class NowPlayingClockMode(val label: String) {
    TV_ONLY("TV Mode Only"),
    MUSIC_ONLY("Music Mode Only"),
    ALL_MODES("All Modes"),
    OFF("Off");

    fun showsClock(isTvMode: Boolean): Boolean = when (this) {
        TV_ONLY -> isTvMode
        MUSIC_ONLY -> !isTvMode
        ALL_MODES -> true
        OFF -> false
    }
}

// Current time, refreshed right as each minute turns over rather than every
// second - the Now Playing clock never shows seconds.
@Composable
fun rememberMinuteClock(): Calendar {
    var now by remember { mutableStateOf(Calendar.getInstance()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Calendar.getInstance()
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    return now
}

private const val ERROR_FLASH_MS = 4000L

// Red banner for the newest command error. Driven by the error's own
// timestamp rather than by "a new value arrived", so it disappears after
// ERROR_FLASH_MS for good - re-composing a screen (Cover Flow <-> Now
// Playing, app <-> screensaver) only shows it for whatever time it has left.
@Composable
fun CommandErrorFlash(latest: CommandError?, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    // Kept after hiding so the text doesn't blank out mid exit-animation.
    var shown by remember { mutableStateOf<CommandError?>(null) }
    LaunchedEffect(latest?.id) {
        val error = latest ?: return@LaunchedEffect
        val remaining = ERROR_FLASH_MS - (System.currentTimeMillis() - error.timestampMs)
        if (remaining <= 0) return@LaunchedEffect
        shown = error
        visible = true
        delay(remaining)
        visible = false
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier.padding(top = 40.dp)
    ) {
        Surface(
            color = Color.Red,
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 12.dp,
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            Text(
                text = shown?.message ?: "",
                color = Color.White,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }
    }
}

@Composable
fun MetadataDebuggerOverlay(
    show: Boolean,
    trackState: TrackState,
    onClose: () -> Unit
) {
    if (show) {
        Surface(
            color = Color.White,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .padding(bottom = 20.dp),
            border = BorderStroke(3.dp, Color.Black),
            shadowElevation = 100.dp
        ) {
            Column(modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                Text("METADATA DEBUGGER", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 22.sp)
                Spacer(Modifier.height(16.dp))
                
                Text("LAST COMMAND STATUS:", color = Color.Blue, fontWeight = FontWeight.Bold)
                Text(trackState.lastCommandSent, color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Medium)

                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color.Black.copy(alpha = 0.2f))

                val context = LocalContext.current
                val recentErrors by remember { SonosDashboardManager.getInstance(context) }
                    .recentErrors.collectAsState()
                Text("RECENT ERRORS (LAST 5):", color = Color.Blue, fontWeight = FontWeight.Bold)
                if (recentErrors.isEmpty()) {
                    Text("None", color = Color.DarkGray)
                } else {
                    val timeFormat = remember { java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                    recentErrors.forEach { error ->
                        Text(
                            "${timeFormat.format(java.util.Date(error.timestampMs))}  ${error.message}",
                            color = Color(0xFFC62828),
                            fontSize = 14.sp
                        )
                    }
                }
                
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color.Black.copy(alpha = 0.2f))
                
                Text("CURRENTLY PLAYING:", color = Color.Blue, fontWeight = FontWeight.Bold)
                Text("TITLE: ${trackState.title}", color = Color.Black)
                Text("ARTIST: ${trackState.artist}", color = Color.Black)
                Text("ALBUM: ${trackState.album}", color = Color.Black)
                Text(
                    "POSITION: ${trackState.positionSeconds}s / DURATION: ${trackState.durationSeconds}s" +
                        if (trackState.durationSeconds <= 0) "  (no duration reported - progress bar stays hidden)" else "",
                    color = Color.Black
                )

                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color.Black.copy(alpha = 0.2f))
                
                Text("SYSTEM METADATA (XML):", color = Color.DarkGray, fontWeight = FontWeight.Bold)
                Text(trackState.rawMetadata, color = Color.DarkGray, fontSize = 12.sp)
                
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color.Black.copy(alpha = 0.2f))

                Text("TEST MODE:", color = Color.Blue, fontWeight = FontWeight.Bold)
                val fallbackCount = TvFallbackArt.imageUrls(LocalContext.current).size
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("TV fallback artwork", color = Color.Black, fontWeight = FontWeight.Medium)
                        Text(
                            if (fallbackCount == 0) "No images found in assets/tv_fallback/"
                            else "Forces the fallback art on and cycles all $fallbackCount image(s) every 5s",
                            color = Color.DarkGray,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = TvFallbackArt.testMode,
                        onCheckedChange = { TvFallbackArt.testMode = it },
                        enabled = fallbackCount > 0 || TvFallbackArt.testMode
                    )
                }

                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onClose, 
                    modifier = Modifier.align(Alignment.End),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black)
                ) {
                    Text("Close Debugger", color = Color.White)
                }
            }
        }
    }
}

// Matches the green Sonos itself uses to indicate a muted speaker.
private val SonosMuteGreen = Color(0xFF4CAF50)

// A long-press timeout (~500ms) is easy to trigger by accident just
// resting a finger on the screen - the metadata debugger only means to be
// reachable deliberately, so it needs a real hold instead. Kept well under
// the 10s paused-idle timer that drops Now Playing back to Cover Flow, which
// a 10s hold used to race (and lose to).
private const val DEBUG_TOGGLE_HOLD_MS = 3_000L

// Watches the Initial pass, so it sees the press even when a child (album
// art card, button, pager) or a sibling drag detector would otherwise
// consume it first - which is what made the old detectTapGestures version
// so hard to trigger. Tolerates a few touch-slops of finger drift, since
// nobody holds perfectly still for seconds. Once triggered, the rest of the
// gesture is consumed so releasing doesn't also count as a tap/click on
// whatever was underneath.
private fun Modifier.debugToggleHoldGesture(onTrigger: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val maxDrift = viewConfiguration.touchSlop * 4
        val endedEarly = withTimeoutOrNull(DEBUG_TOGGLE_HOLD_MS) {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed || (change.position - down.position).getDistance() > maxDrift) break
            }
            true
        }
        if (endedEarly == null) {
            onTrigger()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
}

@Composable
fun LargeVolumeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    isMuted: Boolean = false,
    fillColor: Color = Color.White.copy(alpha = 0.6f),
    height: Dp = 80.dp,
    fontSize: TextUnit = 24.sp,
    modifier: Modifier = Modifier
) {
    var width by remember { mutableIntStateOf(0) }
    var isDragging by remember { mutableStateOf(false) }
    var localValue by remember { mutableFloatStateOf(value) }

    LaunchedEffect(value) {
        if (!isDragging) {
            localValue = value
        }
    }

    val animatedValue by animateFloatAsState(
        targetValue = localValue,
        animationSpec = if (isDragging) spring(stiffness = Spring.StiffnessHigh) else tween(300),
        label = "volumeAnim"
    )

    // Only running the infinite pulse animation while actually muted lets
    // Compose's frame clock go idle the rest of the time - unconditionally
    // starting it (even if the resulting alpha went unused while not muted)
    // would keep this composable redrawing every frame forever.
    val muteFlashAlpha = if (isMuted) {
        val muteFlashTransition = rememberInfiniteTransition(label = "volumeMuteFlash")
        val alpha by muteFlashTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(500, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "muteFlashAlpha"
        )
        alpha
    } else 1f
    val effectiveFillColor = if (isMuted) SonosMuteGreen.copy(alpha = muteFlashAlpha) else fillColor
    val labelColorOnFill = if (isMuted) Color.White else Color.Black
    // Fill may be semi-transparent (the default) - keep the label solid.
    val labelColorOnTrack = if (isMuted) SonosMuteGreen.copy(alpha = muteFlashAlpha) else fillColor.copy(alpha = 1f)

    Box(
        modifier = modifier
            .height(height)
            .fillMaxWidth()
            .clip(RoundedCornerShape(height / 2))
            .background(Color.White.copy(alpha = 0.15f))
            .onSizeChanged { width = it.width }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (width > 0) {
                        val newValue = (offset.x / width) * 100f
                        localValue = newValue.coerceIn(0f, 100f)
                        onValueChange(localValue)
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = {
                        isDragging = false
                        onValueChange(localValue)
                    },
                    onDragCancel = { isDragging = false },
                    onDrag = { change, _ ->
                        if (width > 0) {
                            val newValue = (change.position.x / width) * 100f
                            localValue = newValue.coerceIn(0f, 100f)
                        }
                    }
                )
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animatedValue / 100f)
                .background(effectiveFillColor)
        )

        // The label sits across the fill boundary, so a single color is
        // unreadable on one side or the other - draw it twice, each copy
        // clipped to just the filled/unfilled half, so it reads black over
        // the white fill and white over the dark track like a HUD label.
        val fillWidthPx = width * (animatedValue / 100f)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent { clipRect(right = fillWidthPx) { this@drawWithContent.drawContent() } }
        ) {
            Text(
                text = "${localValue.roundToInt()}%",
                color = labelColorOnFill,
                fontWeight = FontWeight.Black,
                fontSize = fontSize,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent { clipRect(left = fillWidthPx) { this@drawWithContent.drawContent() } }
        ) {
            Text(
                text = "${localValue.roundToInt()}%",
                color = labelColorOnTrack,
                fontWeight = FontWeight.Black,
                fontSize = fontSize,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
fun SmallVolumeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var width by remember { mutableIntStateOf(0) }
    var isDragging by remember { mutableStateOf(false) }
    var localValue by remember { mutableFloatStateOf(value) }
    
    LaunchedEffect(value) {
        if (!isDragging) localValue = value
    }
    
    val animatedValue by animateFloatAsState(
        targetValue = localValue,
        animationSpec = if (isDragging) spring(stiffness = Spring.StiffnessHigh) else tween(300),
        label = "smallVolAnim"
    )

    Box(
        modifier = modifier
            .height(40.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .onSizeChanged { width = it.width }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (width > 0) {
                        val newValue = (offset.x / width) * 100f
                        localValue = newValue.coerceIn(0f, 100f)
                        onValueChange(localValue)
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { 
                        isDragging = false
                        onValueChange(localValue)
                    },
                    onDragCancel = { isDragging = false },
                    onDrag = { change, _ ->
                        if (width > 0) {
                            val newValue = (change.position.x / width) * 100f
                            localValue = newValue.coerceIn(0f, 100f)
                        }
                    }
                )
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animatedValue / 100f)
                .background(Color.White.copy(alpha = 0.7f))
        )

        val fillWidthPx = width * (animatedValue / 100f)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent { clipRect(right = fillWidthPx) { this@drawWithContent.drawContent() } }
        ) {
            Text(
                text = "${localValue.roundToInt()}%",
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent { clipRect(left = fillWidthPx) { this@drawWithContent.drawContent() } }
        ) {
            Text(
                text = "${localValue.roundToInt()}%",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

// Standard graphic-EQ peak-hold ballistics: the cap sits still for a beat,
// then falls back down under accelerating "gravity" until it merges back
// into the bar (rather than fading out in place) - both tuned by eye.
private const val PEAK_HOLD_MS = 1000L
private const val PEAK_GRAVITY = 6f // fraction-of-height per second^2

enum class SpectrumColorScheme(val label: String) {
    RAINBOW("Rainbow"),
    FIRE("Fire"),
    OCEAN("Ocean"),
    NEON("Neon"),
    MONO("Green"),
    ALBUM_ART("Album Art")
}

private fun spectrumBarColor(
    scheme: SpectrumColorScheme,
    bandIndex: Int,
    bandCount: Int,
    albumPalette: List<Color> = emptyList()
): Color {
    val t = bandIndex.toFloat() / (bandCount - 1).coerceAtLeast(1)
    return when (scheme) {
        // Bass=red to treble=violet, full sweep.
        SpectrumColorScheme.RAINBOW -> Color.hsv(t * 300f, 0.9f, 1f)
        // Deep red at bass climbing to bright yellow at treble, like flame licking upward.
        SpectrumColorScheme.FIRE -> Color.hsv(t * 55f, 1f, 1f)
        // Blue through cyan into violet - a cool, watery gradient.
        SpectrumColorScheme.OCEAN -> Color.hsv(200f + t * 80f, 0.85f, 1f)
        // Hot pink through purple into electric cyan - high-contrast/cyberpunk.
        SpectrumColorScheme.NEON -> Color.hsv(320f - t * 130f, 1f, 1f)
        // Single brand accent, no per-band variation.
        SpectrumColorScheme.MONO -> SonosMuteGreen
        // Sweep across the swatches pulled from the current album art (see
        // extractAlbumPalette), falling back to the brand accent if none
        // could be extracted (no art, load failure, monochrome art, etc.).
        SpectrumColorScheme.ALBUM_ART -> albumPaletteColorAt(t, albumPalette)
    }
}

private fun albumPaletteColorAt(t: Float, palette: List<Color>): Color {
    if (palette.isEmpty()) return SonosMuteGreen
    if (palette.size == 1) return palette[0]
    val pos = t * (palette.size - 1)
    val lo = pos.toInt().coerceIn(0, palette.size - 1)
    val hi = (lo + 1).coerceAtMost(palette.size - 1)
    return lerp(palette[lo], palette[hi], pos - lo)
}

@Composable
fun SpectrumAnalyzerView(
    bands: FloatArray,
    frequencyLabels: List<Int>,
    colorScheme: SpectrumColorScheme = SpectrumColorScheme.RAINBOW,
    albumPalette: List<Color> = emptyList(),
    showFrequencyLabels: Boolean = true,
    modifier: Modifier = Modifier
) {
    val bandCount = bands.size
    if (bandCount == 0) return

    // Bar heights: instant attack, quick decay so it visibly jumps with the beat.
    val smoothed = remember(bandCount) { FloatArray(bandCount) }
    // Per-band peak-hold: the height it's marking, when it was last set, and
    // its current fall speed once gravity kicks in after the hold period.
    val peakHeights = remember(bandCount) { FloatArray(bandCount) }
    val peakSetAt = remember(bandCount) { LongArray(bandCount) }
    val peakVelocities = remember(bandCount) { FloatArray(bandCount) }
    var frameTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // LaunchedEffect below only restarts when bandCount changes, so it must
    // read bands through a holder that's updated every recomposition -
    // otherwise its coroutine keeps closing over the array reference from
    // whichever composition first launched it, and bars freeze on that frame.
    val latestBands by rememberUpdatedState(bands)

    LaunchedEffect(bandCount) {
        var lastTick = System.currentTimeMillis()
        while (isActive) {
            val now = System.currentTimeMillis()
            val dt = (now - lastTick).coerceAtLeast(1) / 1000f
            lastTick = now
            for (i in 0 until bandCount) {
                val target = latestBands.getOrElse(i) { 0f }.coerceIn(0f, 1f)
                smoothed[i] = if (target > smoothed[i]) target else smoothed[i] * 0.55f + target * 0.45f
                if (smoothed[i] >= peakHeights[i]) {
                    peakHeights[i] = smoothed[i]
                    peakSetAt[i] = now
                    peakVelocities[i] = 0f
                } else if (now - peakSetAt[i] > PEAK_HOLD_MS) {
                    peakVelocities[i] += PEAK_GRAVITY * dt
                    peakHeights[i] = (peakHeights[i] - peakVelocities[i] * dt).coerceAtLeast(smoothed[i])
                }
            }
            frameTime = now
            delay(33) // ~30fps - a screensaver visualizer doesn't need 60, and this halves the redraw cost
        }
    }

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .let { if (showFrequencyLabels) it.weight(1f) else it.fillMaxHeight() }
        ) {
            // smoothed/peakHeights are plain arrays mutated in place (not Compose
            // State), so reading frameTime here is what makes this redraw every
            // tick - dropping it would freeze the canvas even as the data updates.
            frameTime
            val gap = 4.dp.toPx()
            val barWidth = (size.width - gap * (bandCount - 1)) / bandCount
            val peakMarkHeight = 2.dp.toPx()

            for (i in 0 until bandCount) {
                val x = i * (barWidth + gap)
                val barH = smoothed[i] * size.height
                val barColor = spectrumBarColor(colorScheme, i, bandCount, albumPalette)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(barColor.copy(alpha = 0.55f), barColor.copy(alpha = 0.25f)),
                        startY = size.height - barH,
                        endY = size.height
                    ),
                    topLeft = Offset(x, size.height - barH),
                    size = Size(barWidth, barH)
                )

                // No fade - the cap just holds, then falls under gravity until it
                // merges back into the bar, like a standard hardware/software EQ.
                if (peakHeights[i] > 0.01f) {
                    val peakY = size.height - (peakHeights[i] * size.height)
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(x, (peakY - peakMarkHeight).coerceAtLeast(0f)),
                        size = Size(barWidth, peakMarkHeight)
                    )
                }
            }
        }

        if (showFrequencyLabels) {
            Spacer(modifier = Modifier.height(4.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                frequencyLabels.forEach { freq ->
                    Text(
                        text = formatFrequencyLabel(freq),
                        fontSize = 8.sp,
                        color = Color.White.copy(alpha = 0.45f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

private fun formatFrequencyLabel(freqHz: Int): String {
    if (freqHz < 1000) return "$freqHz"
    val k = freqHz / 1000f
    return if (k == k.toInt().toFloat()) "${k.toInt()}k" else String.format(Locale.getDefault(), "%.1fk", k)
}

private fun formatScreensaverDelay(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return when {
        minutes == 0 -> "${seconds}s"
        seconds == 0 -> "${minutes}m"
        else -> "${minutes}m ${seconds}s"
    }
}

private fun formatTrackTime(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}

// The 2s poll only updates positionSeconds in discrete jumps, which would
// make the displayed clock visibly stutter - resync to the polled value on
// every change, then tick up locally once a second in between polls so it
// reads like a smooth, live counter.
@Composable
private fun rememberInterpolatedPosition(trackState: TrackState): Int {
    var displayedSeconds by remember { mutableIntStateOf(trackState.positionSeconds) }
    LaunchedEffect(trackState.positionSeconds, trackState.isPlaying, trackState.durationSeconds) {
        displayedSeconds = trackState.positionSeconds
        if (trackState.isPlaying) {
            while (isActive) {
                delay(1000)
                displayedSeconds = (displayedSeconds + 1).coerceAtMost(trackState.durationSeconds)
            }
        }
    }
    return displayedSeconds
}

/**
 * Pulls a handful of representative swatches out of the current album art
 * for the "Album Art" spectrum color scheme. Downscaled before decoding
 * since Palette only needs a rough color read, not full resolution, and
 * `allowHardware(false)` is required - Palette reads pixels back off the
 * bitmap, which a GPU-backed hardware bitmap won't allow.
 */
private suspend fun extractAlbumPalette(context: Context, imageUrl: String): List<Color> {
    if (imageUrl.isBlank()) return emptyList()
    return withContext(Dispatchers.IO) {
        try {
            val request = ImageRequest.Builder(context)
                .data(imageUrl)
                .size(120, 120)
                .allowHardware(false)
                .build()
            val bitmap = (context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap
                ?: return@withContext emptyList()
            val palette = Palette.from(bitmap).generate()
            val swatches = listOfNotNull(
                palette.vibrantSwatch,
                palette.lightVibrantSwatch,
                palette.darkVibrantSwatch,
                palette.mutedSwatch,
                palette.lightMutedSwatch,
                palette.darkMutedSwatch
            )
            // Order bass-to-treble by hue, like the other schemes' sweeps, so
            // the palette reads as a gradient instead of a random shuffle.
            swatches.map { Color(it.rgb) }.sortedBy { color ->
                val hsv = FloatArray(3)
                android.graphics.Color.colorToHSV(color.toArgb(), hsv)
                hsv[0]
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

@Composable
fun LabeledIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color.Black.copy(alpha = 0.4f),
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = label, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text(label, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

// Frosted-glass look without a real backdrop blur: the dashboard behind it is
// already heavily blurred album art, so a translucent top-lit fill, a thin
// bright rim and a soft shadow read as glass at no rendering cost.
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(40.dp)
            .shadow(elevation = 6.dp, shape = CircleShape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.White.copy(alpha = 0.26f), Color.White.copy(alpha = 0.10f))
                ),
                CircleShape
            )
            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Color.White)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DockDashboardUI(
    trackState: TrackState,
    haState: HAState,
    isTvMode: Boolean,
    currentVolume: Float,
    onCommand: (String) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onSpeakerVolumeChange: (String, Float) -> Unit,
    onToggleMute: () -> Unit,
    onToggleShuffle: () -> Unit = {},
    onBrightnessChange: (Int) -> Unit,
    onEnterAmbient: (ScreenSlide) -> Unit,
    albumHistory: List<AlbumHistoryItem>,
    onOpenSettings: () -> Unit,
    speakerGroups: List<SonosSpeakerGroup>,
    onToggleJoin: (String, Boolean) -> Unit,
    onAlbumArtPositioned: (Rect) -> Unit = {},
    onSetClimateTemp: (Double?, Double?, Double?) -> Unit = { _, _, _ -> },
    onSetClimateMode: (String) -> Unit = {},
    onSetClimateFan: (String) -> Unit = {},
    onSetClimatePreset: (String) -> Unit = {},
    onSetLightOn: (Boolean) -> Unit = {},
    onSetLightColorRgb: (Int, Int, Int) -> Unit = { _, _, _ -> },
    onSetLightColorTemp: (Int) -> Unit = {},
    showSpectrumAnalyzer: Boolean = false,
    micPermissionGranted: Boolean = false,
    onRequestMicPermission: () -> Unit = {},
    spectrumColorScheme: SpectrumColorScheme = SpectrumColorScheme.RAINBOW,
    spectrumHeightPercent: Float = 1f,
    spectrumConfineToAlbumArt: Boolean = false,
    audioSettings: SonosAudioSettings = SonosAudioSettings(),
    // Settings > Now Playing > Track Info.
    hidePrevious: Boolean = false,
    hideNextUp: Boolean = false,
    // Settings > Now Playing > Clock.
    clockMode: NowPlayingClockMode = NowPlayingClockMode.OFF,
    use24HourClock: Boolean = true,
    onToggleNightMode: () -> Unit = {},
    onToggleDialogMode: () -> Unit = {},
    onSetBass: (Int) -> Unit = {},
    onSetTreble: (Int) -> Unit = {},
    onSetLoudness: (Boolean) -> Unit = {},
    onToggleSubEnabled: () -> Unit = {}
) {
    var showDebugInfo by remember { mutableStateOf(false) }
    var showGroupingModal by remember { mutableStateOf(false) }
    var showThermostat by remember { mutableStateOf(false) }
    var showLightDialog by remember { mutableStateOf(false) }
    var showSoundSettings by remember { mutableStateOf(false) }
    var previousTrackDetails by remember { mutableStateOf<TrackDetails?>(null) }

    val spectrumAnalyzer = remember { SpectrumAnalyzer() }
    val spectrumBands by spectrumAnalyzer.bands.collectAsState()
    DisposableEffect(Unit) {
        onDispose { spectrumAnalyzer.stop() }
    }
    LaunchedEffect(showSpectrumAnalyzer, micPermissionGranted, trackState.isPlaying) {
        if (showSpectrumAnalyzer && micPermissionGranted && trackState.isPlaying) {
            spectrumAnalyzer.start(this)
        } else {
            spectrumAnalyzer.stop()
        }
    }

    val context = LocalContext.current
    val artworkUrl = TvFallbackArt.rememberArtworkUrl(isTvMode, trackState.albumArtUrl)
    var albumPalette by remember { mutableStateOf<List<Color>>(emptyList()) }
    LaunchedEffect(artworkUrl, spectrumColorScheme, showSpectrumAnalyzer) {
        albumPalette = if (showSpectrumAnalyzer && spectrumColorScheme == SpectrumColorScheme.ALBUM_ART) {
            extractAlbumPalette(context, artworkUrl)
        } else {
            emptyList()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .debugToggleHoldGesture { showDebugInfo = !showDebugInfo }
            .pointerInput(Unit) {
                // Swipe down, left or right anywhere on the background to
                // jump to Cover Flow, same as the button below - a descendant
                // control (volume slider, buttons, etc.) that starts its own
                // drag/click already consumes the touch, so this only fires
                // for swipes starting on empty background. Sideways matters
                // for the screensaver: there, SystemUI's dream overlay
                // (Pixel Tablet) claims every swipe down for the
                // notification shade before the app ever sees it.
                val thresholdPx = 100.dp.toPx()
                var totalX = 0f
                var totalY = 0f
                detectDragGestures(
                    onDragStart = { totalX = 0f; totalY = 0f },
                    onDrag = { _, dragAmount ->
                        totalX += dragAmount.x
                        totalY += dragAmount.y
                    },
                    onDragEnd = {
                        val isSideways = abs(totalX) > abs(totalY)
                        if (isSideways && abs(totalX) > thresholdPx) {
                            onEnterAmbient(if (totalX > 0) ScreenSlide.FROM_LEFT else ScreenSlide.FROM_RIGHT)
                        } else if (!isSideways && totalY > thresholdPx) {
                            onEnterAmbient(ScreenSlide.VERTICAL)
                        }
                    }
                )
            }
    ) {
        if (artworkUrl.isNotBlank()) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(40.dp)
                    .alpha(0.55f)
            )
        }

        if (showSpectrumAnalyzer && micPermissionGranted && trackState.isPlaying && !spectrumConfineToAlbumArt) {
            // Sits above the blurred background but below everything else in
            // this Box (top bar, album art, text, controls) purely by
            // composition order - later children draw on top. Anchored to
            // the bottom so spectrumHeightPercent < 1 shrinks it up from the
            // bottom edge rather than from the top.
            SpectrumAnalyzerView(
                bands = spectrumBands,
                frequencyLabels = spectrumAnalyzer.bandFrequencies,
                colorScheme = spectrumColorScheme,
                albumPalette = albumPalette,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(spectrumHeightPercent.coerceIn(0.1f, 1f))
                    .padding(horizontal = 32.dp, vertical = 16.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    color = Color.Black.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.clickable { showThermostat = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (haState.isFanRunning) {
                            val infiniteTransition = rememberInfiniteTransition(label = "fan")
                            val rotation by infiniteTransition.animateFloat(
                                initialValue = 0f,
                                targetValue = 360f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1500, easing = LinearEasing),
                                    repeatMode = RepeatMode.Restart
                                ),
                                label = "fanRotation"
                            )
                            Icon(
                                Icons.Default.Cyclone,
                                contentDescription = null,
                                tint = Color.Cyan,
                                modifier = Modifier.rotate(rotation)
                            )
                        } else {
                            Icon(Icons.Default.Thermostat, contentDescription = null, tint = Color.White)
                        }
                        
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${haState.climateTemp} • ${haState.hvacState.uppercase()}",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Surface(
                    color = if (haState.lightOn) Color.Yellow.copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.4f),
                    shape = CircleShape,
                    modifier = Modifier.clickable { showLightDialog = true }
                ) {
                    Box(modifier = Modifier.padding(10.dp)) {
                        Icon(
                            Icons.Default.Lightbulb,
                            contentDescription = "Light",
                            tint = if (haState.lightOn) Color.Black else Color.White
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Visualizer is switched on but has no mic to listen with - offer
            // the fix right here rather than silently showing nothing.
            if (showSpectrumAnalyzer && !micPermissionGranted) {
                LabeledIconButton(
                    icon = Icons.Default.MicOff,
                    label = "Turn On Mic for Visualizer",
                    onClick = onRequestMicPermission
                )
            }

            LabeledIconButton(
                icon = Icons.Default.BurstMode,
                label = "Cover Flow",
                onClick = { onEnterAmbient(ScreenSlide.VERTICAL) }
            )

            LabeledIconButton(
                icon = Icons.Default.Settings,
                label = "Settings",
                onClick = onOpenSettings
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 70.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // Square for album art; in TV mode it slowly letterboxes to
                // 16:9 (same width, top and bottom closing in), like a movie
                // starting - and opens back up when music returns.
                val artAspectRatio by animateFloatAsState(
                    targetValue = if (isTvMode) 16f / 9f else 1f,
                    animationSpec = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
                    label = "artAspectRatio"
                )
                if (clockMode.showsClock(isTvMode)) {
                    val currentTime = rememberMinuteClock()
                    Text(
                        text = formatClockTime(currentTime, use24HourClock),
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Light,
                        color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = 12.dp)
                    )
                }
                Card(
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(artAspectRatio)
                        .onGloballyPositioned { onAlbumArtPositioned(it.boundsInWindow()) }
                ) {
                    // Wrapped in a Box (rather than leaving Card's default Column
                    // content scope) so the confine-to-album-art spectrum overlay
                    // below can stack on top of the art instead of being laid out
                    // as a second, height-starved Column child underneath it.
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (artworkUrl.isNotBlank()) {
                            AsyncImage(
                                model = artworkUrl,
                                contentDescription = "Album Cover",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.DarkGray),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(64.dp)
                                )
                            }
                        }

                        if (showSpectrumAnalyzer && micPermissionGranted && trackState.isPlaying && spectrumConfineToAlbumArt) {
                            // Card clips its content to its shape, so this is
                            // automatically cropped to the same rounded square.
                            SpectrumAnalyzerView(
                                bands = spectrumBands,
                                frequencyLabels = spectrumAnalyzer.bandFrequencies,
                                colorScheme = spectrumColorScheme,
                                albumPalette = albumPalette,
                                showFrequencyLabels = false,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp)
                            )
                        }
                    }
                }

                // TV's HA-sourced media player only gives us a point-in-time
                // duration/position, not a track list - "time left" reads more
                // naturally here than an elapsed/total pair. Sized large and
                // centered under the artwork so it reads from across a room.
                if (isTvMode && trackState.durationSeconds > 0) {
                    val displayedSeconds = rememberInterpolatedPosition(trackState)
                    val remainingSeconds = (trackState.durationSeconds - displayedSeconds).coerceAtLeast(0)
                    Spacer(modifier = Modifier.height(20.dp))
                    LinearProgressIndicator(
                        progress = { (displayedSeconds.toFloat() / trackState.durationSeconds).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Color.White.copy(alpha = 0.8f),
                        trackColor = Color.White.copy(alpha = 0.2f)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "${formatTrackTime(remainingSeconds)} remaining",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.width(48.dp))

            Column(
                modifier = Modifier.weight(1.2f),
                verticalArrangement = Arrangement.Center
            ) {
                if (!isTvMode) {
                    if (trackState.prevTrack != "None" && !hidePrevious) {
                        Surface(
                            color = Color.White.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { previousTrackDetails = trackState.previousTrackDetails() }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                if (trackState.prevArtUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = trackState.prevArtUrl,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(
                                    text = "PREVIOUS: ${trackState.prevTrack}",
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = trackState.title,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(
                        text = if (trackState.album.isNotBlank()) "${trackState.artist} — ${trackState.album}" else trackState.artist,
                        fontSize = 20.sp,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }

                // Radio/live streams (and anything else the source can't
                // time) report a 0 duration - skip the row rather than show
                // a stuck or meaningless "0:00 / 0:00". TV mode gets its own
                // progress bar centered under the artwork instead (above).
                if (!isTvMode && trackState.durationSeconds > 0) {
                    val displayedSeconds = rememberInterpolatedPosition(trackState)
                    Text(
                        text = "${formatTrackTime(displayedSeconds)} / ${formatTrackTime(trackState.durationSeconds)}",
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (!isTvMode && trackState.nextTrack != "None" && !hideNextUp) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = Color.White.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            if (trackState.nextArtUrl.isNotBlank()) {
                                AsyncImage(
                                    model = trackState.nextArtUrl,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = "NEXT UP: ${trackState.nextTrack}",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { onCommand("previous") },
                        modifier = Modifier
                            .size(56.dp)
                            .background(Color.White.copy(alpha = 0.2f), CircleShape)
                    ) {
                        Icon(Icons.Default.SkipPrevious, "Previous", tint = Color.White)
                    }

                    FloatingActionButton(
                        onClick = { onCommand(if (trackState.isPlaying) "pause" else "play") },
                        containerColor = Color.White,
                        contentColor = Color.Black,
                        shape = CircleShape,
                        modifier = Modifier.size(72.dp)
                    ) {
                        Icon(
                            imageVector = if (trackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    IconButton(
                        onClick = { onCommand("next") },
                        modifier = Modifier
                            .size(56.dp)
                            .background(Color.White.copy(alpha = 0.2f), CircleShape)
                    ) {
                        Icon(Icons.Default.SkipNext, "Next", tint = Color.White)
                    }

                    // Only the Sonos queue can be shuffled, so no button for
                    // radio, line-in, TV etc. Filled white while on.
                    if (!isTvMode && trackState.shuffleAvailable) {
                        IconButton(
                            onClick = onToggleShuffle,
                            modifier = Modifier
                                .size(56.dp)
                                .background(
                                    if (trackState.isShuffled) Color.White else Color.White.copy(alpha = 0.2f),
                                    CircleShape
                                )
                        ) {
                            Icon(
                                Icons.Default.Shuffle,
                                if (trackState.isShuffled) "Shuffle on" else "Shuffle off",
                                tint = if (trackState.isShuffled) Color.Black else Color.White
                            )
                        }
                    }
                }

                // Speaker-hardware features rather than playback controls -
                // the settings button (below, next to volume) only shows up
                // once the coordinator reports support for at least one of
                // these (e.g. Night Sound/Speech Enhancement are soundbar-
                // only), instead of a dead button on speakers with none of it.
                val hasSoundSettings = audioSettings.nightMode != null ||
                    audioSettings.dialogMode != null ||
                    audioSettings.bass != null ||
                    audioSettings.treble != null ||
                    audioSettings.loudness != null ||
                    audioSettings.subEnabled != null

                Spacer(modifier = Modifier.height(30.dp))

                val joinedMembers = trackState.groupMembers.filter { !it.isCoordinator }
                
                Column(
                    modifier = Modifier.fillMaxWidth(0.9f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (trackState.groupMembers.size > 1) {
                            val coordinator = trackState.groupMembers.find { it.isCoordinator }
                            Icon(
                                painter = painterResource(id = sonosModelIconRes(coordinator?.model ?: "")),
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = (coordinator?.name ?: "").uppercase(),
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(120.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        
                        // Same reasoning as LargeVolumeSlider's muteFlashAlpha - only
                        // animate while actually muted so this stops keeping the
                        // frame clock alive the rest of the time.
                        val muteIconAlpha = if (trackState.isMuted) {
                            val muteIconFlash = rememberInfiniteTransition(label = "muteIconFlash")
                            val alpha by muteIconFlash.animateFloat(
                                initialValue = 1f,
                                targetValue = 0.25f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(500, easing = LinearEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "muteIconAlpha"
                            )
                            alpha
                        } else 1f
                        Icon(
                            imageVector = if (trackState.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeDown,
                            contentDescription = "Mute Toggle",
                            tint = if (trackState.isMuted) SonosMuteGreen.copy(alpha = muteIconAlpha) else Color.White,
                            modifier = Modifier
                                .size(32.dp)
                                .background(
                                    if (trackState.isMuted) SonosMuteGreen.copy(alpha = 0.2f * muteIconAlpha) else Color.Transparent,
                                    CircleShape
                                )
                                .clickable { onToggleMute() }
                                .padding(4.dp)
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        LargeVolumeSlider(
                            value = currentVolume,
                            onValueChange = { onVolumeChange(it) },
                            isMuted = trackState.isMuted,
                            modifier = Modifier.weight(1f)
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Volume High",
                            tint = Color.White
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        GlassIconButton(
                            icon = Icons.Default.SpeakerGroup,
                            contentDescription = "Speaker Groups",
                            onClick = { showGroupingModal = true }
                        )

                        if (hasSoundSettings) {
                            Spacer(modifier = Modifier.width(16.dp))

                            GlassIconButton(
                                icon = Icons.Default.Tune,
                                contentDescription = "Sound Settings",
                                onClick = { showSoundSettings = true }
                            )
                        }
                    }

                    joinedMembers.forEach { member ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(id = sonosModelIconRes(member.model)),
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = member.name.uppercase(),
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(120.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(modifier = Modifier.width(48.dp))

                            SmallVolumeSlider(
                                value = member.volume,
                                onValueChange = { onSpeakerVolumeChange(member.name, it) },
                                modifier = Modifier.weight(1f)
                            )
                            
                            Spacer(modifier = Modifier.width(48.dp))
                        }
                    }
                }
            }
        }

        MetadataDebuggerOverlay(
            show = showDebugInfo,
            trackState = trackState,
            onClose = { showDebugInfo = false }
        )

        if (showGroupingModal) {
            GroupingModal(
                speakerGroups = speakerGroups,
                onToggleJoin = onToggleJoin,
                onDismiss = { showGroupingModal = false }
            )
        }

        if (showThermostat) {
            ThermostatDialog(
                haState = haState,
                onSetTemp = onSetClimateTemp,
                onSetMode = onSetClimateMode,
                onSetFan = onSetClimateFan,
                onSetPreset = onSetClimatePreset,
                onDismiss = { showThermostat = false }
            )
        }

        if (showLightDialog) {
            LightDialog(
                haState = haState,
                onSetOn = onSetLightOn,
                onSetBrightness = onBrightnessChange,
                onSetColorRgb = onSetLightColorRgb,
                onSetColorTemp = onSetLightColorTemp,
                onDismiss = { showLightDialog = false }
            )
        }

        previousTrackDetails?.let { track ->
            TrackDetailsDialog(
                heading = "Previous Track",
                track = track,
                onDismiss = { previousTrackDetails = null }
            )
        }

        if (showSoundSettings) {
            SoundSettingsDialog(
                audioSettings = audioSettings,
                onToggleNightMode = onToggleNightMode,
                onToggleDialogMode = onToggleDialogMode,
                onSetBass = onSetBass,
                onSetTreble = onSetTreble,
                onSetLoudness = onSetLoudness,
                onToggleSubEnabled = onToggleSubEnabled,
                onDismiss = { showSoundSettings = false }
            )
        }
    }
}

// Whether the system is actually blurring behind the current GlassDialog -
// GlassCard goes more opaque when it isn't, so text stays readable over
// sharp album art.
private val LocalGlassBlurActive = compositionLocalOf { false }

// Dashboard pop-up with a real frosted-glass backdrop: on Android 12+ the
// dialog window blurs everything behind it (cross-window blur), and the
// translucent GlassCard inside lets that blur show through. The system can
// turn blur off at any time (battery saver, some devices never support it),
// so this listens for that and falls back to a plain dim.
@Composable
fun GlassDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    // Inside the screensaver: drawn in the dream's own window, which blurs
    // what's behind it itself (see InWindowDialogs.kt).
    if (LocalInWindowDialogHost.current != null) {
        DockDialog(onDismissRequest = onDismissRequest) {
            CompositionLocalProvider(LocalGlassBlurActive provides inWindowBlurSupported) {
                content()
            }
        }
        return
    }

    Dialog(onDismissRequest = onDismissRequest) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val blurRadiusPx = with(LocalDensity.current) { 30.dp.roundToPx() }
        var blurActive by remember { mutableStateOf(false) }

        DisposableEffect(window) {
            if (window == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return@DisposableEffect onDispose { }
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = blurRadiusPx }
            val listener = Consumer<Boolean> { enabled ->
                blurActive = enabled
                // Blur already separates the card from the dashboard, so only
                // a light dim is needed; without it, dim harder instead.
                window.setDimAmount(if (enabled) 0.2f else 0.5f)
            }
            window.windowManager.addCrossWindowBlurEnabledListener(window.context.mainExecutor, listener)
            onDispose { window.windowManager.removeCrossWindowBlurEnabledListener(listener) }
        }

        CompositionLocalProvider(LocalGlassBlurActive provides blurActive) {
            content()
        }
    }
}

// The translucent card inside a GlassDialog - light charcoal glass with a
// faint rim that's brighter along the top edge, like light catching it.
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        color = Color(0xFF2C2C30).copy(alpha = if (LocalGlassBlurActive.current) 0.72f else 0.92f),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(
            1.dp,
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.06f)))
        ),
        modifier = modifier,
        content = content
    )
}

@Composable
fun GroupingModal(
    speakerGroups: List<SonosSpeakerGroup>,
    onToggleJoin: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    // The real join/unjoin can take a few seconds to land on the speaker and
    // flow back through polling - reflect the tap immediately here instead
    // of leaving the switch looking unresponsive, then drop the override
    // once the polled state actually catches up to it.
    val optimisticJoin = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(speakerGroups) {
        speakerGroups.forEach { group ->
            if (optimisticJoin[group.name] == group.isJoined) {
                optimisticJoin.remove(group.name)
            }
        }
    }
    fun displayedJoin(group: SonosSpeakerGroup) = optimisticJoin[group.name] ?: group.isJoined
    fun setJoin(name: String, join: Boolean) {
        optimisticJoin[name] = join
        onToggleJoin(name, join)
    }

    // Lighter than Color.Blue so "Joined" reads on the dark glass.
    val joinedBlue = Color(0xFF64B5F6)

    GlassDialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.width(420.dp)) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Speaker Groups", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                if (speakerGroups.isEmpty()) {
                    Text("Searching for other speakers...", color = Color.Gray)
                } else {
                    val anyJoined = speakerGroups.any { displayedJoin(it) }
                    val anyAvailable = speakerGroups.any { !displayedJoin(it) }
                    if (anyJoined || anyAvailable) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (anyAvailable) {
                                OutlinedButton(
                                    onClick = {
                                        speakerGroups.filter { !displayedJoin(it) }
                                            .forEach { setJoin(it.name, true) }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
                                ) { Text("Join All") }
                            }
                            if (anyJoined) {
                                OutlinedButton(
                                    onClick = {
                                        speakerGroups.filter { displayedJoin(it) }
                                            .forEach { setJoin(it.name, false) }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
                                ) { Text("Unjoin All") }
                            }
                        }
                    }
                    speakerGroups.forEach { group ->
                        val isJoined = displayedJoin(group)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isJoined) joinedBlue.copy(alpha = 0.15f) else Color.Transparent)
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(id = sonosModelIconRes(group.model)),
                                    contentDescription = null,
                                    tint = if (isJoined) joinedBlue else Color.Gray,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(group.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                                    Text(if (isJoined) "Joined" else "Available", fontSize = 12.sp, color = if (isJoined) joinedBlue else Color.Gray)
                                }
                            }
                            Switch(
                                checked = isJoined,
                                onCheckedChange = { setJoin(group.name, it) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// Maps a Sonos model_name (from SoCo's get_speaker_info) to a per-model
// icon - falls back to the generic Sonos wordmark for anything we don't
// have a dedicated icon for (Arc/Move's traced icons were impractically
// huge for a 24dp glyph, Play:1 etc. have none in the pack at all).
private fun sonosModelIconRes(model: String): Int {
    val m = model.lowercase()
    return when {
        "sub mini" in m -> R.drawable.ic_sonos_sub_mini
        "sub" in m -> R.drawable.ic_sonos_sub
        "playbase" in m -> R.drawable.ic_sonos_playbase
        "playbar" in m -> R.drawable.ic_sonos_playbar
        "beam" in m -> R.drawable.ic_sonos_beam
        "era 100" in m -> R.drawable.ic_sonos_era_100
        "era 300" in m -> R.drawable.ic_sonos_era_300
        "roam" in m -> R.drawable.ic_sonos_roam
        "port" in m -> R.drawable.ic_sonos_port
        "one" in m -> R.drawable.ic_sonos_one
        else -> R.drawable.ic_sonos
    }
}

private fun roundToStep(value: Double, step: Double): Double {
    if (step <= 0.0) return value
    return Math.round(value / step) * step
}

private fun formatTemp(v: Double): String {
    val rounded = Math.round(v * 10.0) / 10.0
    return if (rounded == Math.floor(rounded)) rounded.toInt().toString() else rounded.toString()
}

private fun hvacModeIcon(mode: String): ImageVector = when (mode) {
    "cool" -> Icons.Default.AcUnit
    "heat" -> Icons.Default.LocalFireDepartment
    "heat_cool", "auto" -> Icons.Default.Thermostat
    "dry" -> Icons.Default.WaterDrop
    "fan_only" -> Icons.Default.Cyclone
    "off" -> Icons.Default.PowerSettingsNew
    else -> Icons.Default.Thermostat
}

private fun hvacModeColor(mode: String): Color = when (mode) {
    "cool" -> Color(0xFF2196F3)
    "heat" -> Color(0xFFFF9800)
    "heat_cool", "auto" -> Color(0xFF4CAF50)
    "dry" -> Color(0xFFFFC107)
    "fan_only" -> Color(0xFF9C27B0)
    else -> Color.Gray
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThermostatDialog(
    haState: HAState,
    onSetTemp: (Double?, Double?, Double?) -> Unit,
    onSetMode: (String) -> Unit,
    onSetFan: (String) -> Unit,
    onSetPreset: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val isRange = haState.targetTempHigh != null && haState.targetTempLow != null
    // HA reports whatever step the integration declares (often 0.1 or 1.0),
    // but a 0.5° resolution is the natural granularity for Celsius
    // thermostats - force it regardless of what the entity advertises.
    val effectiveStep = if (haState.temperatureUnit.contains("C", ignoreCase = true)) 0.5 else haState.tempStep
    var localTemp by remember(haState.targetTemp) { mutableStateOf(roundToStep(haState.targetTemp ?: haState.minTemp, effectiveStep)) }
    var localHigh by remember(haState.targetTempHigh) { mutableStateOf(roundToStep(haState.targetTempHigh ?: haState.maxTemp, effectiveStep)) }
    var localLow by remember(haState.targetTempLow) { mutableStateOf(roundToStep(haState.targetTempLow ?: haState.minTemp, effectiveStep)) }

    val modeColor = hvacModeColor(haState.hvacState)
    val scope = rememberCoroutineScope()
    var pendingSend by remember { mutableStateOf<Job?>(null) }

    fun scheduleSend() {
        pendingSend?.cancel()
        pendingSend = scope.launch {
            delay(600)
            if (isRange) onSetTemp(null, localHigh, localLow) else onSetTemp(localTemp, null, null)
        }
    }

    GlassDialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.width(420.dp)) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Climate", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                Spacer(Modifier.height(12.dp))

                Box(
                    modifier = Modifier.size(320.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(260.dp)) {
                        val strokeWidth = 22.dp.toPx()
                        val startAngle = 135f
                        val sweepAngle = 270f
                        val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                        val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
                        val range = (haState.maxTemp - haState.minTemp).coerceAtLeast(0.1)

                        drawArc(
                            color = Color.White.copy(alpha = 0.12f),
                            startAngle = startAngle,
                            sweepAngle = sweepAngle,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )

                        if (isRange) {
                            val lowFrac = ((localLow - haState.minTemp) / range).coerceIn(0.0, 1.0)
                            val highFrac = ((localHigh - haState.minTemp) / range).coerceIn(0.0, 1.0)
                            drawArc(
                                color = modeColor,
                                startAngle = startAngle + sweepAngle * lowFrac.toFloat(),
                                sweepAngle = (sweepAngle * (highFrac - lowFrac).toFloat()).coerceAtLeast(0f),
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                            )
                        } else {
                            val frac = ((localTemp - haState.minTemp) / range).coerceIn(0.0, 1.0)
                            drawArc(
                                color = modeColor,
                                startAngle = startAngle,
                                sweepAngle = sweepAngle * frac.toFloat(),
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                            )
                        }
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (isRange) {
                            Text(
                                "${formatTemp(localLow)}°–${formatTemp(localHigh)}°",
                                color = Color.White,
                                fontSize = 34.sp,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Text(
                                "${formatTemp(localTemp)}°",
                                color = Color.White,
                                fontSize = 52.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            "Current: ${haState.climateTemp}${haState.temperatureUnit}" +
                                (haState.currentHumidity?.let { " · $it% humidity" } ?: ""),
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                        if (haState.hvacAction.isNotBlank()) {
                            Text(
                                haState.hvacAction.uppercase(),
                                color = modeColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            if (isRange) localLow = roundToStep((localLow - effectiveStep).coerceAtLeast(haState.minTemp), effectiveStep)
                            else localTemp = roundToStep((localTemp - effectiveStep).coerceAtLeast(haState.minTemp), effectiveStep)
                            scheduleSend()
                        },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(bottom = 4.dp, start = 4.dp)
                            .background(Color.White.copy(alpha = 0.1f), CircleShape)
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease temperature", tint = Color.White)
                    }
                    IconButton(
                        onClick = {
                            if (isRange) localHigh = roundToStep((localHigh + effectiveStep).coerceAtMost(haState.maxTemp), effectiveStep)
                            else localTemp = roundToStep((localTemp + effectiveStep).coerceAtMost(haState.maxTemp), effectiveStep)
                            scheduleSend()
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 4.dp, end = 4.dp)
                            .background(Color.White.copy(alpha = 0.1f), CircleShape)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Increase temperature", tint = Color.White)
                    }
                }

                Spacer(Modifier.height(20.dp))

                if (haState.hvacModes.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        haState.hvacModes.forEach { mode ->
                            val selected = haState.hvacState == mode
                            FilterChip(
                                selected = selected,
                                onClick = { onSetMode(mode) },
                                label = { Text(mode.replace("_", " ").replaceFirstChar { it.uppercase() }) },
                                leadingIcon = {
                                    Icon(hvacModeIcon(mode), contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Color.White.copy(alpha = 0.08f),
                                    labelColor = Color.White,
                                    iconColor = Color.White,
                                    selectedContainerColor = hvacModeColor(mode),
                                    selectedLabelColor = Color.White,
                                    selectedLeadingIconColor = Color.White
                                )
                            )
                        }
                    }
                }

                if (haState.fanModes.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Text("Fan", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Start))
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        haState.fanModes.forEach { fan ->
                            FilterChip(
                                selected = haState.fanMode == fan,
                                onClick = { onSetFan(fan) },
                                label = { Text(fan.replace("_", " ").replaceFirstChar { it.uppercase() }) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Color.White.copy(alpha = 0.08f),
                                    labelColor = Color.White,
                                    selectedContainerColor = Color.White.copy(alpha = 0.25f),
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                }

                if (haState.presetModes.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Text("Preset", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Start))
                    Spacer(Modifier.height(6.dp))

                    var presetMenuExpanded by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Surface(
                            color = Color.White.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { presetMenuExpanded = true }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    haState.presetMode.ifBlank { "Select preset" }
                                        .replace("_", " ")
                                        .replaceFirstChar { it.uppercase() },
                                    color = Color.White
                                )
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.White)
                            }
                        }
                        DropdownMenu(
                            expanded = presetMenuExpanded,
                            onDismissRequest = { presetMenuExpanded = false },
                            modifier = Modifier.background(Color(0xFF222222))
                        ) {
                            haState.presetModes.forEach { preset ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            preset.replace("_", " ").replaceFirstChar { it.uppercase() },
                                            color = Color.White
                                        )
                                    },
                                    onClick = {
                                        onSetPreset(preset)
                                        presetMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val LIGHT_PRESET_COLORS = listOf(
    "Red" to Triple(255, 0, 0),
    "Orange" to Triple(255, 140, 0),
    "Yellow" to Triple(255, 220, 0),
    "Green" to Triple(0, 200, 80),
    "Cyan" to Triple(0, 200, 200),
    "Blue" to Triple(30, 100, 255),
    "Purple" to Triple(160, 60, 255),
    "Pink" to Triple(255, 60, 180),
    "White" to Triple(255, 255, 255)
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LightDialog(
    haState: HAState,
    onSetOn: (Boolean) -> Unit,
    onSetBrightness: (Int) -> Unit,
    onSetColorRgb: (Int, Int, Int) -> Unit,
    onSetColorTemp: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var localBrightness by remember(haState.lightBrightness) { mutableStateOf(haState.lightBrightness.toFloat()) }
    val scope = rememberCoroutineScope()
    var pendingBrightness by remember { mutableStateOf<Job?>(null) }

    fun scheduleBrightness(value: Int) {
        pendingBrightness?.cancel()
        pendingBrightness = scope.launch {
            delay(300)
            onSetBrightness(value)
        }
    }

    val supportsRgb = haState.lightColorModes.any { it in listOf("rgb", "rgbw", "rgbww", "hs", "xy") }
    val supportsColorTemp = !supportsRgb && haState.lightColorModes.any { it == "color_temp" }

    var localKelvin by remember(haState.lightColorTempKelvin) {
        mutableStateOf((haState.lightColorTempKelvin ?: ((haState.lightMinColorTempKelvin + haState.lightMaxColorTempKelvin) / 2)).toFloat())
    }
    var pendingTemp by remember { mutableStateOf<Job?>(null) }

    fun scheduleTemp(value: Int) {
        pendingTemp?.cancel()
        pendingTemp = scope.launch {
            delay(300)
            onSetColorTemp(value)
        }
    }

    GlassDialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.width(380.dp)) {
            Box {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Light", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }

                    // Same faint white sections as the Thermostat and Sound
                    // Settings pop-ups.
                    Surface(
                        color = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(if (haState.lightOn) "On" else "Off", color = Color.White, fontSize = 16.sp)
                            Switch(
                                checked = haState.lightOn,
                                onCheckedChange = onSetOn,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Yellow,
                                    checkedTrackColor = Color.Yellow.copy(alpha = 0.5f)
                                )
                            )
                        }
                    }

                    Surface(
                        color = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Brightness", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(10.dp))
                            LargeVolumeSlider(
                                value = localBrightness / 255f * 100f,
                                onValueChange = {
                                    val brightness = (it / 100f * 255f).roundToInt()
                                    localBrightness = brightness.toFloat()
                                    scheduleBrightness(brightness)
                                },
                                fillColor = Color(0xFFFFD54F),
                                height = 64.dp,
                                fontSize = 20.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .alpha(if (haState.lightOn) 1f else 0.4f)
                            )
                        }
                    }

                    if (supportsRgb) {
                        Surface(
                            color = Color.White.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Color", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(10.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    LIGHT_PRESET_COLORS.forEach { (name, rgb) ->
                                        val (r, g, b) = rgb
                                        // Lights that store color natively as xy/hs (most
                                        // Hue-style bulbs) round-trip an exact rgb_color
                                        // we send into a fairly different value once
                                        // clipped to their gamut, so match loosely by
                                        // total distance rather than per-channel equality.
                                        val isSelected = haState.lightRgbColor?.let {
                                            it.size == 3 &&
                                                (kotlin.math.abs(it[0] - r) + kotlin.math.abs(it[1] - g) + kotlin.math.abs(it[2] - b)) < 150
                                        } ?: false
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(Color(r, g, b))
                                                .border(
                                                    width = if (isSelected) 3.dp else 1.dp,
                                                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.3f),
                                                    shape = CircleShape
                                                )
                                                .clickable { onSetColorRgb(r, g, b) }
                                        )
                                    }
                                }
                            }
                        }
                    } else if (supportsColorTemp) {
                        Surface(
                            color = Color.White.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Color Temperature", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(10.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Warm", color = Color(0xFFFFB74D), fontSize = 11.sp)
                                    Slider(
                                        value = localKelvin,
                                        onValueChange = {
                                            localKelvin = it
                                            scheduleTemp(it.toInt())
                                        },
                                        valueRange = haState.lightMinColorTempKelvin.toFloat()..haState.lightMaxColorTempKelvin.toFloat(),
                                        enabled = haState.lightOn,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 8.dp),
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color.White,
                                            activeTrackColor = Color.White.copy(alpha = 0.6f),
                                            inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                                        )
                                    )
                                    Text("Cool", color = Color(0xFF64B5F6), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SoundSettingsDialog(
    audioSettings: SonosAudioSettings,
    onToggleNightMode: () -> Unit,
    onToggleDialogMode: () -> Unit,
    onSetBass: (Int) -> Unit,
    onSetTreble: (Int) -> Unit,
    onSetLoudness: (Boolean) -> Unit,
    onToggleSubEnabled: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var localBass by remember(audioSettings.bass) { mutableFloatStateOf((audioSettings.bass ?: 0).toFloat()) }
    var pendingBass by remember { mutableStateOf<Job?>(null) }
    fun scheduleBass(value: Int) {
        pendingBass?.cancel()
        pendingBass = scope.launch {
            delay(300)
            onSetBass(value)
        }
    }

    var localTreble by remember(audioSettings.treble) { mutableFloatStateOf((audioSettings.treble ?: 0).toFloat()) }
    var pendingTreble by remember { mutableStateOf<Job?>(null) }
    fun scheduleTreble(value: Int) {
        pendingTreble?.cancel()
        pendingTreble = scope.launch {
            delay(300)
            onSetTreble(value)
        }
    }

    GlassDialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.width(380.dp)) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Sound Settings", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                if (audioSettings.bass != null) {
                    Surface(
                        color = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text("Bass: ${localBass.roundToInt()}", color = Color.White, fontSize = 16.sp)
                            Slider(
                                value = localBass,
                                onValueChange = {
                                    localBass = it
                                    scheduleBass(it.roundToInt())
                                },
                                valueRange = -10f..10f,
                                steps = 19,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = Color.White.copy(alpha = 0.6f),
                                    inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                                )
                            )
                        }
                    }
                }

                if (audioSettings.treble != null) {
                    Surface(
                        color = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text("Treble: ${localTreble.roundToInt()}", color = Color.White, fontSize = 16.sp)
                            Slider(
                                value = localTreble,
                                onValueChange = {
                                    localTreble = it
                                    scheduleTreble(it.roundToInt())
                                },
                                valueRange = -10f..10f,
                                steps = 19,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = Color.White.copy(alpha = 0.6f),
                                    inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                                )
                            )
                        }
                    }
                }

                val hasNightMode = audioSettings.nightMode != null
                val hasDialogMode = audioSettings.dialogMode != null
                val hasLoudness = audioSettings.loudness != null
                val hasSubToggle = audioSettings.subEnabled != null

                if (hasNightMode || hasDialogMode || hasLoudness || hasSubToggle) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (hasNightMode) {
                            val active = audioSettings.nightMode == true
                            SoundToggleButton(
                                icon = Icons.Default.Bedtime,
                                contentDescription = "Night Sound",
                                active = active,
                                activeColor = Color(0xFF6C63FF),
                                activeIconTint = Color(0xFFD0CBFF),
                                onClick = onToggleNightMode
                            )
                        }

                        if (hasDialogMode) {
                            val active = audioSettings.dialogMode == true
                            SoundToggleButton(
                                icon = Icons.Default.RecordVoiceOver,
                                contentDescription = "Speech Enhancement",
                                active = active,
                                activeColor = SonosMuteGreen,
                                activeIconTint = Color(0xFFB9F6B4),
                                onClick = onToggleDialogMode
                            )
                        }

                        if (hasLoudness) {
                            val active = audioSettings.loudness == true
                            SoundToggleButton(
                                icon = Icons.Default.GraphicEq,
                                contentDescription = "Loudness",
                                active = active,
                                activeColor = Color(0xFFFFA726),
                                activeIconTint = Color(0xFFFFE0B2),
                                onClick = { onSetLoudness(!active) }
                            )
                        }

                        if (hasSubToggle) {
                            val active = audioSettings.subEnabled == true
                            SoundToggleButton(
                                icon = Icons.Default.Speaker,
                                contentDescription = "Subwoofer",
                                active = active,
                                activeColor = Color(0xFFEF5350),
                                activeIconTint = Color(0xFFFFCDD2),
                                onClick = onToggleSubEnabled
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SoundToggleButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    activeColor: Color,
    activeIconTint: Color,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(60.dp)
                .background(
                    if (active) activeColor.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.08f),
                    CircleShape
                )
        ) {
            Icon(icon, contentDescription = contentDescription, tint = if (active) activeIconTint else Color.White)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(contentDescription, color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntityAutocompleteField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<String>,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val filtered = remember(value, suggestions) {
        if (value.isBlank()) suggestions
        else suggestions.filter { it.contains(value, ignoreCase = true) }
    }

    ExposedDropdownMenuBox(
        expanded = expanded && filtered.isNotEmpty(),
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded && filtered.isNotEmpty(),
            onDismissRequest = { expanded = false }
        ) {
            filtered.take(50).forEach { entity ->
                DropdownMenuItem(
                    text = { Text(entity) },
                    onClick = {
                        onValueChange(entity)
                        expanded = false
                    }
                )
            }
        }
    }
}

// Settings > Now Playing's TV Mode Artwork choice: "Rotate Randomly" (saved
// as "") followed by a thumbnail of every image in assets/tv_fallback/, so
// newly added images appear here on their own.
@Composable
private fun TvFallbackImagePicker(selected: String, onSelect: (String) -> Unit) {
    val urls = TvFallbackArt.imageUrls(LocalContext.current)
    // A saved image that's since been removed falls back to rotating.
    val effective = if (urls.any { TvFallbackArt.nameOf(it) == selected }) selected else ""
    val tileModifier = Modifier.size(width = 112.dp, height = 63.dp)

    @Composable
    fun Tile(isSelected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
        Box(
            modifier = tileModifier
                .clip(RoundedCornerShape(8.dp))
                .border(
                    width = if (isSelected) 3.dp else 1.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp)
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Tile(isSelected = effective.isEmpty(), onClick = { onSelect("") }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(20.dp))
                Text("Rotate Randomly", fontSize = 11.sp)
            }
        }
        urls.forEach { url ->
            val name = TvFallbackArt.nameOf(url)
            Tile(isSelected = effective == name, onClick = { onSelect(name) }) {
                AsyncImage(
                    model = url,
                    contentDescription = name.substringBeforeLast('.'),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

// Status line beside Settings > Home Assistant's Connect button.
@Composable
private fun HaConnectionIndicator(status: HaConnectionStatus, modifier: Modifier = Modifier) {
    val (color, text) = when (status) {
        HaConnectionStatus.NotTested -> Color.Gray to "Not connected - tap Connect to check"
        HaConnectionStatus.Testing -> Color.Gray to "Connecting..."
        is HaConnectionStatus.Connected -> Color(0xFF2E7D32) to buildString {
            append("Connected to ${status.serverName}")
            if (status.version.isNotBlank()) append(" (v${status.version})")
        }
        is HaConnectionStatus.Failed -> Color(0xFFC62828) to status.reason
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (status == HaConnectionStatus.Testing) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        } else {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        }
        Spacer(Modifier.width(8.dp))
        Text(text, color = color, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// Compact label + switch row for the Settings dialog.
@Composable
private fun SettingsToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsModal(
    discoveredSpeakers: List<String>,
    settingsManager: SettingsManager,
    refreshStatus: String,
    refreshCount: Int? = null,
    albumHistoryCount: Int,
    onRefreshFavorites: (Boolean, Boolean, Boolean) -> Unit,
    onClearHistory: () -> Unit,
    onSettingsSaved: () -> Unit,
    onDismiss: () -> Unit,
    micPermissionGranted: Boolean = false,
    onRequestMicPermission: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()

    var selectedSpeaker by remember { mutableStateOf("") }
    var haUrl by remember { mutableStateOf("") }
    var haToken by remember { mutableStateOf("") }
    var selectedLight by remember { mutableStateOf("") }
    var selectedClimate by remember { mutableStateOf("") }
    var selectedHaMediaPlayer by remember { mutableStateOf("") }
    var coverFlowMode by remember { mutableStateOf("history") }
    var coverFlowLimit by remember { mutableIntStateOf(SettingsManager.COVER_FLOW_LIMIT_DEFAULT) }
    var use24HourClock by remember { mutableStateOf(true) }
    var coverFlowReflection by remember { mutableFloatStateOf(SettingsManager.COVER_FLOW_REFLECTION_DEFAULT) }
    var coverFlowGlassTable by remember { mutableFloatStateOf(SettingsManager.COVER_FLOW_GLASS_TABLE_DEFAULT) }
    var pendingCoverFlowGlass by remember { mutableStateOf<Job?>(null) }
    var tvScreensaverDelaySeconds by remember { mutableIntStateOf(SettingsManager.TV_SCREENSAVER_DELAY_DEFAULT_SECONDS) }
    var pendingScreensaverDelay by remember { mutableStateOf<Job?>(null) }
    var coverFlowSideLight by remember { mutableFloatStateOf(SettingsManager.COVER_FLOW_SIDE_LIGHT_DEFAULT) }
    var hidePrevious by remember { mutableStateOf(false) }
    var hideNextUp by remember { mutableStateOf(false) }
    var hideNowPlaying by remember { mutableStateOf(false) }
    var nowPlayingHidePrevious by remember { mutableStateOf(false) }
    var nowPlayingClockMode by remember { mutableStateOf(NowPlayingClockMode.OFF) }
    var autoUnmuteAfterAds by remember { mutableStateOf(false) }
    var continueWithRandomAlbum by remember { mutableStateOf(false) }
    var nowPlayingHideNextUp by remember { mutableStateOf(false) }
    var tvFallbackImage by remember { mutableStateOf("") }
    var showSpectrumAnalyzer by remember { mutableStateOf(false) }
    var spectrumColorScheme by remember { mutableStateOf(SpectrumColorScheme.RAINBOW) }
    var spectrumHeightPercent by remember { mutableFloatStateOf(1f) }
    var spectrumConfineToAlbumArt by remember { mutableStateOf(false) }
    var pendingSpectrumHeight by remember { mutableStateOf<Job?>(null) }

    var fAlbums by remember { mutableStateOf(true) }
    var fPlaylists by remember { mutableStateOf(true) }
    var fRadio by remember { mutableStateOf(true) }

    var showClearHistoryConfirm by remember { mutableStateOf(false) }
    // Every saved History album, including any hidden by the Cover Flow
    // limit - Clear History removes all of them.
    val savedHistoryCount by remember { settingsManager.albumHistory.map { it.size } }
        .collectAsState(initial = albumHistoryCount)

    val haManager = remember { HomeAssistantManager() }
    var lightEntities by remember { mutableStateOf<List<String>>(emptyList()) }
    var climateEntities by remember { mutableStateOf<List<String>>(emptyList()) }
    var mediaPlayerEntities by remember { mutableStateOf<List<String>>(emptyList()) }

    var haConnection by remember { mutableStateOf<HaConnectionStatus>(HaConnectionStatus.NotTested) }

    // Tests the URL + token, and on success (re)loads the entity lists so
    // the fields below autocomplete against whatever server just connected.
    fun connectToHa() {
        haConnection = HaConnectionStatus.Testing
        val url = haUrl
        val token = haToken
        haManager.testConnection(url, token) { status ->
            // Ignore a stale result if the fields changed while it ran.
            if (url != haUrl || token != haToken) return@testConnection
            haConnection = status
            if (status is HaConnectionStatus.Connected) {
                haManager.fetchEntities(url, token) { all ->
                    lightEntities = all.filter { it.startsWith("light.") }.sorted()
                    climateEntities = all.filter { it.startsWith("climate.") }.sorted()
                    mediaPlayerEntities = all.filter { it.startsWith("media_player.") }.sorted()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        selectedSpeaker = settingsManager.sonosSpeaker.first()
        haUrl = settingsManager.haUrl.first()
        haToken = settingsManager.haToken.first()
        selectedLight = settingsManager.selectedLight.first()
        selectedClimate = settingsManager.selectedClimate.first()
        selectedHaMediaPlayer = settingsManager.selectedHaMediaPlayer.first()
        coverFlowMode = settingsManager.coverFlowMode.first()
        coverFlowLimit = settingsManager.coverFlowLimit.first()
        use24HourClock = settingsManager.use24HourClock.first()
        coverFlowReflection = settingsManager.coverFlowReflection.first()
        coverFlowGlassTable = settingsManager.coverFlowGlassTable.first()
        tvScreensaverDelaySeconds = settingsManager.tvScreensaverDelaySeconds.first()
        coverFlowSideLight = settingsManager.coverFlowSideLight.first()
        hidePrevious = settingsManager.coverFlowHidePrevious.first()
        hideNextUp = settingsManager.coverFlowHideNextUp.first()
        hideNowPlaying = settingsManager.coverFlowHideNowPlaying.first()
        nowPlayingHidePrevious = settingsManager.nowPlayingHidePrevious.first()
        nowPlayingHideNextUp = settingsManager.nowPlayingHideNextUp.first()
        nowPlayingClockMode = settingsManager.nowPlayingClockMode.first()
        autoUnmuteAfterAds = settingsManager.autoUnmuteAfterAds.first()
        continueWithRandomAlbum = settingsManager.continueWithRandomAlbum.first()
        tvFallbackImage = settingsManager.tvFallbackImage.first()
        fAlbums = settingsManager.filterAlbums.first()
        fPlaylists = settingsManager.filterPlaylists.first()
        fRadio = settingsManager.filterRadio.first()
        showSpectrumAnalyzer = settingsManager.showSpectrumAnalyzer.first()
        spectrumColorScheme = settingsManager.spectrumColorScheme.first()
        spectrumHeightPercent = settingsManager.spectrumHeightPercent.first()
        spectrumConfineToAlbumArt = settingsManager.spectrumConfineToAlbumArt.first()

        // Check the saved connection straight away, so the status shows
        // without needing to tap Connect - this also loads the entity lists
        // the fields below autocomplete from.
        if (haUrl.isNotBlank() && haToken.isNotBlank()) {
            connectToHa()
        }
    }

    val modes = listOf(
        "history" to "Mode A: History of Favorited Albums",
        "favorites" to "Mode B: Albums, Playlists & Radio"
    )

    val tabs = listOf("General", "Cover Flow", "Now Playing", "Home Assistant")
    var selectedTab by remember { mutableIntStateOf(0) }

    // The refresh status line doesn't visibly change when a re-scan finds
    // the same count as last time, so the button itself reports this
    // press's result. Kept out here so switching tabs doesn't reset it.
    var refreshPressed by remember { mutableStateOf(false) }
    val isRefreshing = refreshPressed && refreshStatus.startsWith("Starting")

    // Fixed height so the dialog doesn't jump around when switching tabs -
    // anything taller than this scrolls within the tab.
    val tabContentHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp

    DockAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 960.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dashboard Configuration", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("v${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = Color.Gray)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, maxLines = 1) }
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(tabContentHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (selectedTab) {
                        // GENERAL: which speaker, and dock-wide display basics
                        0 -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(24.dp)
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("Sonos Speaker:", fontWeight = FontWeight.SemiBold)
                                    if (discoveredSpeakers.isEmpty()) {
                                        Text("Scanning Wi-Fi for speakers...", fontSize = 12.sp, color = Color.Gray)
                                    } else {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            discoveredSpeakers.forEach { speaker ->
                                                FilterChip(
                                                    selected = (speaker == selectedSpeaker),
                                                    onClick = { selectedSpeaker = speaker },
                                                    label = { Text(speaker) }
                                                )
                                            }
                                        }
                                    }
                                }

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("Clock Format:", fontWeight = FontWeight.SemiBold)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(
                                            selected = !use24HourClock,
                                            onClick = {
                                                use24HourClock = false
                                                scope.launch { settingsManager.setUse24HourClock(false) }
                                            },
                                            label = { Text("12-Hour") }
                                        )
                                        FilterChip(
                                            selected = use24HourClock,
                                            onClick = {
                                                use24HourClock = true
                                                scope.launch { settingsManager.setUse24HourClock(true) }
                                            },
                                            label = { Text("24-Hour") }
                                        )
                                    }
                                    HorizontalDivider()

                                    Text("TV Screensaver Delay:", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "Time on Now Playing before the TV screensaver starts: ${formatScreensaverDelay(tvScreensaverDelaySeconds)}",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                    Slider(
                                        value = tvScreensaverDelaySeconds.toFloat(),
                                        onValueChange = {
                                            tvScreensaverDelaySeconds = (it.roundToInt() / 30) * 30
                                            pendingScreensaverDelay?.cancel()
                                            pendingScreensaverDelay = scope.launch {
                                                delay(300)
                                                settingsManager.setTvScreensaverDelaySeconds(tvScreensaverDelaySeconds)
                                            }
                                        },
                                        valueRange = SettingsManager.TV_SCREENSAVER_DELAY_MIN_SECONDS.toFloat()..
                                            SettingsManager.TV_SCREENSAVER_DELAY_MAX_SECONDS.toFloat(),
                                        // One tick per 30s step between the endpoints.
                                        steps = (SettingsManager.TV_SCREENSAVER_DELAY_MAX_SECONDS -
                                            SettingsManager.TV_SCREENSAVER_DELAY_MIN_SECONDS) / 30 - 1
                                    )
                                    HorizontalDivider()

                                    Text("When an Album Ends:", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "Starts another random album from your Sonos Favorites. Doesn't apply to radio.",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                    SettingsToggleRow("Continue With a Random Album", continueWithRandomAlbum) {
                                        continueWithRandomAlbum = it
                                        scope.launch { settingsManager.setContinueWithRandomAlbum(it) }
                                    }
                                }
                            }
                        }

                        // COVER FLOW: what it shows, its track cards, and the
                        // glass table look
                        1 -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(24.dp)
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("Cover Flow Mode:", fontWeight = FontWeight.SemiBold)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        modes.forEach { (mode, label) ->
                                            FilterChip(
                                                selected = (coverFlowMode == mode),
                                                onClick = { coverFlowMode = mode },
                                                label = { Text(label) }
                                            )
                                        }
                                    }

                                    Text("Cover Flow Limit:", fontWeight = FontWeight.SemiBold)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        SettingsManager.COVER_FLOW_LIMIT_OPTIONS.forEach { limit ->
                                            FilterChip(
                                                selected = (coverFlowLimit == limit),
                                                onClick = { coverFlowLimit = limit },
                                                label = { Text(if (limit == SettingsManager.COVER_FLOW_LIMIT_UNLIMITED) "Unlimited" else "$limit") }
                                            )
                                        }
                                    }
                                    Text(
                                        if (coverFlowMode == "history") "Shows the most recently captured albums. Older ones are kept, just hidden, until the limit is raised."
                                        else "Shows the first favorites alphabetically.",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )

                                    if (coverFlowMode == "history") {
                                        Text(
                                            "Albums need to be favorited in the Sonos app. This mode just captures the history of when your favorited albums are played.",
                                            fontSize = 12.sp,
                                            color = Color.Gray
                                        )
                                        OutlinedButton(
                                            onClick = { showClearHistoryConfirm = true },
                                            modifier = Modifier.fillMaxWidth(),
                                            enabled = savedHistoryCount > 0,
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
                                        ) {
                                            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text("Clear History ($savedHistoryCount)")
                                        }
                                    } else {
                                        Text("Sync Categories:", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Checkbox(checked = fAlbums, onCheckedChange = { fAlbums = it })
                                                Text("Albums", fontSize = 12.sp)
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Checkbox(checked = fPlaylists, onCheckedChange = { fPlaylists = it })
                                                Text("Playlists", fontSize = 12.sp)
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Checkbox(checked = fRadio, onCheckedChange = { fRadio = it })
                                                Text("Radio", fontSize = 12.sp)
                                            }
                                        }

                                        Button(
                                            onClick = {
                                                refreshPressed = true
                                                onRefreshFavorites(fAlbums, fPlaylists, fRadio)
                                            },
                                            enabled = !isRefreshing,
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = null)
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                when {
                                                    !refreshPressed -> "Refresh Favorites"
                                                    isRefreshing -> "Refreshing..."
                                                    refreshCount != null -> "Refreshed $refreshCount Favorite${if (refreshCount == 1) "" else "s"}"
                                                    else -> "Refresh Failed - Tap to Retry"
                                                }
                                            )
                                        }

                                        Text(
                                            text = "Status: $refreshStatus",
                                            fontSize = 12.sp,
                                            color = if (refreshStatus.contains("Error")) Color.Red else Color.Black,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("Track Cards:", fontWeight = FontWeight.SemiBold)
                                    SettingsToggleRow("Hide Previous", hidePrevious) {
                                        hidePrevious = it
                                        scope.launch { settingsManager.setCoverFlowHidePrevious(it) }
                                    }
                                    SettingsToggleRow("Hide Now Playing", hideNowPlaying) {
                                        hideNowPlaying = it
                                        scope.launch { settingsManager.setCoverFlowHideNowPlaying(it) }
                                    }
                                    SettingsToggleRow("Hide Next Up", hideNextUp) {
                                        hideNextUp = it
                                        scope.launch { settingsManager.setCoverFlowHideNextUp(it) }
                                    }
                                }

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // All three sliders share one debounced save, so
                                    // dragging doesn't write to DataStore on every frame.
                                    fun saveCoverFlowGlass() {
                                        pendingCoverFlowGlass?.cancel()
                                        pendingCoverFlowGlass = scope.launch {
                                            delay(300)
                                            settingsManager.setCoverFlowReflection(coverFlowReflection)
                                            settingsManager.setCoverFlowGlassTable(coverFlowGlassTable)
                                            settingsManager.setCoverFlowSideLight(coverFlowSideLight)
                                        }
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Glass Table:", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                        TextButton(
                                            onClick = {
                                                coverFlowReflection = SettingsManager.COVER_FLOW_REFLECTION_DEFAULT
                                                coverFlowGlassTable = SettingsManager.COVER_FLOW_GLASS_TABLE_DEFAULT
                                                coverFlowSideLight = SettingsManager.COVER_FLOW_SIDE_LIGHT_DEFAULT
                                                saveCoverFlowGlass()
                                            },
                                            enabled = coverFlowReflection != SettingsManager.COVER_FLOW_REFLECTION_DEFAULT ||
                                                coverFlowGlassTable != SettingsManager.COVER_FLOW_GLASS_TABLE_DEFAULT ||
                                                coverFlowSideLight != SettingsManager.COVER_FLOW_SIDE_LIGHT_DEFAULT
                                        ) {
                                            Text("Reset to Default")
                                        }
                                    }
                                    Text(
                                        "Reflection strength: ${(coverFlowReflection * 100).roundToInt()}%",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                    Slider(
                                        value = coverFlowReflection,
                                        onValueChange = {
                                            coverFlowReflection = it
                                            saveCoverFlowGlass()
                                        },
                                        valueRange = 0f..1f
                                    )
                                    Text(
                                        "Glass surface: ${(coverFlowGlassTable * 100).roundToInt()}%",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                    Slider(
                                        value = coverFlowGlassTable,
                                        onValueChange = {
                                            coverFlowGlassTable = it
                                            saveCoverFlowGlass()
                                        },
                                        valueRange = 0f..1f
                                    )
                                    Text(
                                        "Side light: ${(coverFlowSideLight * 100).roundToInt()}%",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                    Slider(
                                        value = coverFlowSideLight,
                                        onValueChange = {
                                            coverFlowSideLight = it
                                            saveCoverFlowGlass()
                                        },
                                        valueRange = 0f..1f
                                    )
                                }
                            }
                        }

                        // NOW PLAYING: track info chips and the mic-driven
                        // spectrum analyzer drawn behind the screen
                        2 -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(24.dp)
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("Track Info:", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "The previous and next tracks shown above and below the song title.",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                    SettingsToggleRow("Hide Previous", nowPlayingHidePrevious) {
                                        nowPlayingHidePrevious = it
                                        scope.launch { settingsManager.setNowPlayingHidePrevious(it) }
                                    }
                                    SettingsToggleRow("Hide Next Up", nowPlayingHideNextUp) {
                                        nowPlayingHideNextUp = it
                                        scope.launch { settingsManager.setNowPlayingHideNextUp(it) }
                                    }
                                    HorizontalDivider()

                                    Text("Clock:", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "The current time, shown above the artwork. Follows the 12/24-hour clock setting.",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        NowPlayingClockMode.entries.forEach { mode ->
                                            FilterChip(
                                                selected = (nowPlayingClockMode == mode),
                                                onClick = {
                                                    nowPlayingClockMode = mode
                                                    scope.launch { settingsManager.setNowPlayingClockMode(mode) }
                                                },
                                                label = { Text(mode.label) }
                                            )
                                        }
                                    }
                                    HorizontalDivider()

                                    Text("TV Mode Artwork:", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "Shown when the TV doesn't provide any artwork.",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                    TvFallbackImagePicker(selected = tvFallbackImage) { name ->
                                        tvFallbackImage = name
                                        scope.launch { settingsManager.setTvFallbackImage(name) }
                                    }
                                }

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Spectrum Analyzer", fontWeight = FontWeight.SemiBold)
                                            Text(
                                                text = if (showSpectrumAnalyzer && !micPermissionGranted) {
                                                    "Needs microphone access - tap to grant"
                                                } else {
                                                    "Uses the mic to show a live visualizer behind Now Playing while music plays. Not calibrated, just for looks."
                                                },
                                                fontSize = 11.sp,
                                                color = if (showSpectrumAnalyzer && !micPermissionGranted) Color(0xFFFFA726) else Color.Gray
                                            )
                                        }
                                        Switch(
                                            checked = showSpectrumAnalyzer,
                                            onCheckedChange = { checked ->
                                                showSpectrumAnalyzer = checked
                                                scope.launch { settingsManager.setShowSpectrumAnalyzer(checked) }
                                                if (checked && !micPermissionGranted) {
                                                    onRequestMicPermission()
                                                }
                                            }
                                        )
                                    }

                                    if (showSpectrumAnalyzer) {
                                        Text("Color Scheme:", fontSize = 12.sp, color = Color.Gray)
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            SpectrumColorScheme.entries.forEach { scheme ->
                                                FilterChip(
                                                    selected = (spectrumColorScheme == scheme),
                                                    onClick = {
                                                        spectrumColorScheme = scheme
                                                        scope.launch { settingsManager.setSpectrumColorScheme(scheme) }
                                                    },
                                                    label = { Text(scheme.label) }
                                                )
                                            }
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("Confine to Album Art", fontSize = 12.sp, color = Color.Gray)
                                                Text(
                                                    "Draw bars only over the album art instead of the full screen.",
                                                    fontSize = 10.sp,
                                                    color = Color.Gray
                                                )
                                            }
                                            Switch(
                                                checked = spectrumConfineToAlbumArt,
                                                onCheckedChange = { checked ->
                                                    spectrumConfineToAlbumArt = checked
                                                    scope.launch { settingsManager.setSpectrumConfineToAlbumArt(checked) }
                                                }
                                            )
                                        }

                                        if (!spectrumConfineToAlbumArt) {
                                            Text(
                                                "Height from bottom: ${(spectrumHeightPercent * 100).roundToInt()}%",
                                                fontSize = 12.sp,
                                                color = Color.Gray
                                            )
                                            Slider(
                                                value = spectrumHeightPercent,
                                                onValueChange = {
                                                    spectrumHeightPercent = it
                                                    pendingSpectrumHeight?.cancel()
                                                    pendingSpectrumHeight = scope.launch {
                                                        delay(300)
                                                        settingsManager.setSpectrumHeightPercent(it)
                                                    }
                                                },
                                                valueRange = 0.1f..1f
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // HOME ASSISTANT: connection and entities
                        3 -> {
                            Text("Home Assistant Integration:", fontWeight = FontWeight.SemiBold)

                            OutlinedTextField(
                                value = haUrl,
                                onValueChange = {
                                    haUrl = it
                                    haConnection = HaConnectionStatus.NotTested
                                },
                                label = { Text("Server URL") },
                                placeholder = { Text("http://homeassistant.local:8123") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = haToken,
                                onValueChange = {
                                    haToken = it
                                    haConnection = HaConnectionStatus.NotTested
                                },
                                label = { Text("Long-Lived Access Token") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = { connectToHa() },
                                    enabled = haUrl.isNotBlank() && haToken.isNotBlank() &&
                                        haConnection != HaConnectionStatus.Testing
                                ) {
                                    Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Connect")
                                }
                                HaConnectionIndicator(haConnection, modifier = Modifier.weight(1f))
                            }

                            EntityAutocompleteField(
                                label = "Light Entity",
                                value = selectedLight,
                                onValueChange = { selectedLight = it },
                                suggestions = lightEntities,
                                modifier = Modifier.fillMaxWidth()
                            )

                            EntityAutocompleteField(
                                label = "Climate Entity",
                                value = selectedClimate,
                                onValueChange = { selectedClimate = it },
                                suggestions = climateEntities,
                                modifier = Modifier.fillMaxWidth()
                            )

                            EntityAutocompleteField(
                                label = "Fallback Media Player",
                                value = selectedHaMediaPlayer,
                                onValueChange = { selectedHaMediaPlayer = it },
                                suggestions = mediaPlayerEntities,
                                modifier = Modifier.fillMaxWidth()
                            )
                            HorizontalDivider()

                            Text("Experimental:", fontWeight = FontWeight.SemiBold)
                            Text(
                                "In TV mode, unmutes the speaker when the media player's time remaining jumps up - usually a sign a commercial break just ended.",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                            SettingsToggleRow("Auto-Unmute After Commercials", autoUnmuteAfterAds) {
                                autoUnmuteAfterAds = it
                                scope.launch { settingsManager.setAutoUnmuteAfterAds(it) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch {
                    settingsManager.saveSettings(
                        selectedSpeaker,
                        haUrl,
                        haToken,
                        selectedLight,
                        selectedClimate,
                        selectedHaMediaPlayer,
                        coverFlowMode,
                        fAlbums,
                        fPlaylists,
                        fRadio,
                        coverFlowLimit
                    )
                    onSettingsSaved()
                    onDismiss()
                }
            }) {
                Text("Save & Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    if (showClearHistoryConfirm) {
        DockAlertDialog(
            onDismissRequest = { showClearHistoryConfirm = false },
            title = { Text("Clear History?") },
            text = { Text("This permanently removes all $savedHistoryCount saved history entries. This can't be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        onClearHistory()
                        showClearHistoryConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

// A track as it was when tapped - so the details pop-up keeps showing that
// track even if the song changes (and "previous" moves on) while it's open.
data class TrackDetails(
    val title: String,
    val artist: String,
    val album: String,
    val artUrl: String
)

fun TrackState.previousTrackDetails() = TrackDetails(
    title = prevTrack,
    artist = prevArtist,
    album = prevAlbum,
    artUrl = prevArtUrl
)

// Glass pop-up with a track's album art, song title, artist and album -
// opened by tapping Previous on Now Playing or Cover Flow.
@Composable
fun TrackDetailsDialog(
    heading: String,
    track: TrackDetails,
    onDismiss: () -> Unit
) {
    GlassDialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.width(380.dp)) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(heading, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                Spacer(Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .size(300.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (track.artUrl.isNotBlank()) {
                        AsyncImage(
                            model = track.artUrl,
                            contentDescription = "Album Cover",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(64.dp)
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                Text(
                    text = track.title,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (track.artist.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = track.artist,
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 17.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (track.album.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = track.album,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// One of Cover Flow's bottom track cards (Previous / Now Playing / Next Up).
@Composable
private fun CoverFlowTrackCard(
    label: String,
    title: String,
    subtitle: String,
    artUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color.Black.copy(alpha = 0.6f),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .width(360.dp)
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = label,
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (artUrl.isNotBlank()) {
                    AsyncImage(
                        model = artUrl,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                }
                Column {
                    Text(
                        text = title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

// Matched by identity rather than index, since History mode reorders the
// list (a played album moves to the front). playUri is the stable ID when
// present; history items without one are identified by their art URL.
private fun coverFlowInitialPage(
    albums: List<AlbumHistoryItem>,
    pageCount: Int,
    centerAlbum: AlbumHistoryItem?
): Int {
    val center = pageCount / 2
    if (albums.isEmpty() || centerAlbum == null) return center
    val index = albums.indexOfFirst {
        if (centerAlbum.playUri.isNotBlank()) it.playUri == centerAlbum.playUri
        else it.artUrl == centerAlbum.artUrl
    }
    if (index < 0) return center
    return center - (center % albums.size) + index
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AmbientClockScreen(
    albumHistory: List<AlbumHistoryItem>,
    trackState: TrackState,
    onPlayAlbum: (AlbumHistoryItem, Rect) -> Unit,
    onRemoveAlbum: (String) -> Unit,
    onWakeUp: () -> Unit,
    use24HourClock: Boolean = true,
    // 0..1 each, from Settings - see SettingsManager.COVER_FLOW_REFLECTION.
    reflectionStrength: Float = SettingsManager.COVER_FLOW_REFLECTION_DEFAULT,
    glassTableStrength: Float = SettingsManager.COVER_FLOW_GLASS_TABLE_DEFAULT,
    sideLightStrength: Float = SettingsManager.COVER_FLOW_SIDE_LIGHT_DEFAULT,
    // In Favorites mode the X permanently deletes the Sonos Favorite (after
    // a confirmation prompt) instead of just hiding the card.
    isFavoritesMode: Boolean = false,
    onDeleteFavorite: (AlbumHistoryItem) -> Unit = {},
    // Album to start centered on, and a callback whenever a new one settles
    // in the center - lets the caller resume the rotation where it left off.
    initialCenterAlbum: AlbumHistoryItem? = null,
    onCenterAlbumChanged: (AlbumHistoryItem) -> Unit = {},
    // Settings > Display > Cover Flow Track Cards.
    hidePrevious: Boolean = false,
    hideNextUp: Boolean = false,
    hideNowPlaying: Boolean = false
) {
    var isFadingOut by remember { mutableStateOf(false) }
    // Favorite awaiting the user's confirm/cancel, and then the one they
    // confirmed (which drives that card's drop animation before deleting).
    var pendingFavoriteDelete by remember { mutableStateOf<AlbumHistoryItem?>(null) }
    var confirmedFavoriteDelete by remember { mutableStateOf<AlbumHistoryItem?>(null) }

    // Fallback if no centered card picked up the confirmed delete (e.g. the
    // pager moved) - delete without the animation rather than leave it
    // pending, where it would fire whenever that card next scrolled in.
    LaunchedEffect(confirmedFavoriteDelete) {
        val item = confirmedFavoriteDelete ?: return@LaunchedEffect
        delay(1500)
        if (confirmedFavoriteDelete == item) {
            confirmedFavoriteDelete = null
            onDeleteFavorite(item)
        }
    }
    val fadeAlpha by animateFloatAsState(
        targetValue = if (isFadingOut) 0f else 1f,
        animationSpec = tween(600),
        label = "fadeAlpha"
    )

    var currentTime by remember { mutableStateOf(Calendar.getInstance()) }
    var clockAlignment by remember { mutableStateOf(Alignment.TopCenter) }
    var showDebugInfo by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = Calendar.getInstance()
            delay(1000)
        }
    }
    
    LaunchedEffect(Unit) {
        val alignments = listOf(Alignment.TopStart, Alignment.TopCenter)
        var currentIndex = 1 
        while (true) {
            delay(120_000) 
            currentIndex = (currentIndex + 1) % alignments.size
            clockAlignment = alignments[currentIndex]
        }
    }

    // A finite virtual page count (previously 1000) runs out after enough
    // auto-advances (~2h13m at 8s/page) - animateScrollToPage() can't go
    // past the last page, so the cover flow silently stops wrapping and
    // sits on one album until the dream restarts. Int.MAX_VALUE looks like
    // the obvious fix but Compose's Pager breaks internally at that count
    // (animateScrollToPage stops moving, and it can crash) - so instead use
    // a large-but-safe count and silently recenter (see the auto-advance
    // effect below) well before it's ever exhausted.
    val internalPageCount = if (albumHistory.isEmpty()) 0 else 100_000
    val pagerState = rememberPagerState(
        initialPage = coverFlowInitialPage(albumHistory, internalPageCount, initialCenterAlbum),
        pageCount = { internalPageCount }
    )

    LaunchedEffect(pagerState, albumHistory) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (albumHistory.isNotEmpty()) onCenterAlbumChanged(albumHistory[page % albumHistory.size])
        }
    }

    val isDragged by pagerState.interactionSource.collectIsDraggedAsState()
    var isUserInteracting by remember { mutableStateOf(false) }
    var showLabels by remember { mutableStateOf(false) }
    var previousTrackDetails by remember { mutableStateOf<TrackDetails?>(null) }

    LaunchedEffect(isUserInteracting, isDragged) {
        if (isUserInteracting || isDragged) {
            showLabels = true
        } else {
            delay(5000) 
            showLabels = false
        }
    }

    LaunchedEffect(albumHistory, isUserInteracting, isDragged, pendingFavoriteDelete) {
        if (albumHistory.isNotEmpty() && pendingFavoriteDelete == null) {
            while (true) {
                delay(8000)
                if (pagerState.pageCount > 0 && !isUserInteracting && !isDragged && !pagerState.isScrollInProgress) {
                    pagerState.animateScrollToPage(
                        pagerState.currentPage + 1,
                        animationSpec = tween(1500, easing = FastOutSlowInEasing)
                    )
                    // The virtual page range is large but still finite - if a
                    // dream session runs long enough to approach either edge,
                    // silently snap (no animation, same album via modulo) back
                    // near the middle so it can keep advancing indefinitely.
                    val page = pagerState.currentPage
                    val count = pagerState.pageCount
                    if (page < 1000 || page > count - 1000) {
                        val center = count / 2
                        val recentered = center - (center % albumHistory.size) + (page % albumHistory.size)
                        pagerState.scrollToPage(recentered)
                    }
                }
            }
        }
    }

    val screenWidth = LocalConfiguration.current.screenWidthDp
    val hPadding = ((screenWidth - 450) / 2).coerceAtLeast(0).dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .debugToggleHoldGesture { showDebugInfo = !showDebugInfo }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onWakeUp() })
            }
    ) {
        // The "glass table" the cards stand on. At strength 0 it's just the
        // original faint gradient; turning it up brightens the surface and
        // adds a lit front edge plus a sheen just below it, like the edge of
        // a glass shelf catching the light.
        val glass = glassTableStrength.coerceIn(0f, 1f)
        val sideLight = sideLightStrength.coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.35f)
                .alpha(fadeAlpha)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.White.copy(alpha = lerp(0.05f, 0.18f, glass)))
                    )
                )
                .drawWithContent {
                    drawContent()
                    if (glass > 0f) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.White.copy(alpha = 0.10f * glass), Color.Transparent),
                                endY = size.height * 0.25f
                            )
                        )
                        drawLine(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.45f * glass),
                                    Color.Transparent
                                )
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(size.width, 0f),
                            strokeWidth = 1.5.dp.toPx()
                        )
                    }
                    if (sideLight > 0f) {
                        // Light coming in from the left: a glow pooled where
                        // it enters the glass near the front edge, a beam
                        // that skims across the surface and fades out toward
                        // the right, and a faint spectral fringe along the
                        // beam where the glass edge splits the light.
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(Color.White.copy(alpha = 0.22f * sideLight), Color.Transparent),
                                center = Offset(0f, size.height * 0.12f),
                                radius = size.width * 0.45f
                            )
                        )
                        // The beam is a round glow stretched sideways into a
                        // long ellipse, so it fades out softly on every side
                        // instead of ending in a hard-edged band.
                        val beamCenter = Offset(0f, size.height * 0.10f)
                        val beamRadius = size.height * 0.30f
                        scale(scaleX = size.width * 0.9f / beamRadius, scaleY = 1f, pivot = beamCenter) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    0f to Color.White.copy(alpha = 0.16f * sideLight),
                                    0.5f to Color.White.copy(alpha = 0.05f * sideLight),
                                    1f to Color.Transparent,
                                    center = beamCenter,
                                    radius = beamRadius
                                ),
                                radius = beamRadius,
                                center = beamCenter
                            )
                        }
                        val fringeTop = size.height * 0.04f
                        drawRect(
                            brush = Brush.horizontalGradient(
                                0f to Color(0xFF7FDBFF).copy(alpha = 0.10f * sideLight),
                                0.18f to Color(0xFFB388FF).copy(alpha = 0.07f * sideLight),
                                0.36f to Color(0xFFFF80AB).copy(alpha = 0.04f * sideLight),
                                0.55f to Color.Transparent
                            ),
                            topLeft = Offset(0f, fringeTop),
                            size = Size(size.width, 2.dp.toPx())
                        )
                    }
                }
        )

        if (albumHistory.isNotEmpty()) {
            Box(Modifier.fillMaxSize().alpha(fadeAlpha), contentAlignment = Alignment.Center) {
                HorizontalPager(
                    state = pagerState,
                    contentPadding = PaddingValues(horizontal = hPadding),
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(y = 30.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) { page ->
                    val actualPage = page % albumHistory.size
                    val albumItem = albumHistory[actualPage]
                    val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                    
                    val scale = lerp(start = 0.6f, stop = 1f, fraction = 1f - abs(pageOffset).coerceIn(0f, 1f))
                    val rotationY = lerp(start = if (pageOffset < 0) -50f else 50f, stop = 0f, fraction = 1f - abs(pageOffset).coerceIn(0f, 1f))

                    val dropAnim = remember { Animatable(0f) }
                    val isBeingRemoved = remember { mutableStateOf(false) }

                    // Only the centered copy animates - with a short list the
                    // same favorite also appears on other (virtual) pages.
                    LaunchedEffect(confirmedFavoriteDelete) {
                        if (confirmedFavoriteDelete == albumItem && page == pagerState.currentPage) {
                            isBeingRemoved.value = true
                        }
                    }

                    LaunchedEffect(isBeingRemoved.value) {
                        if (isBeingRemoved.value) {
                            dropAnim.animateTo(
                                targetValue = -60f,
                                animationSpec = tween(150, easing = LinearOutSlowInEasing)
                            )
                            dropAnim.animateTo(
                                targetValue = 2000f,
                                animationSpec = tween(400, easing = LinearEasing)
                            )
                            if (isFavoritesMode && confirmedFavoriteDelete == albumItem) {
                                confirmedFavoriteDelete = null
                                onDeleteFavorite(albumItem)
                            } else {
                                onRemoveAlbum(albumItem.artUrl)
                            }
                            isBeingRemoved.value = false
                            dropAnim.snapTo(0f)
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(450.dp) 
                            .fillMaxHeight()
                            .offset { IntOffset(0, dropAnim.value.roundToInt()) }
                            .pointerInput(actualPage) {
                                awaitPointerEventScope {
                                    awaitFirstDown(requireUnconsumed = false)
                                    isUserInteracting = true
                                    var isVerticalDrag = false
                                    var dragTotalY = 0f
                                    var dragTotalX = 0f
                                    do {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.first()
                                        val dragAmount = change.position - change.previousPosition
                                        dragTotalX += abs(dragAmount.x)
                                        dragTotalY += abs(dragAmount.y)
                                        if (!isVerticalDrag && dragTotalY > 15f && dragTotalY > dragTotalX) isVerticalDrag = true
                                        if (isVerticalDrag && abs(pageOffset) < 0.2f) {
                                            change.consume()
                                        }
                                    } while (event.changes.any { it.pressed })
                                    isUserInteracting = false
                                }
                            },
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(450.dp)
                                .graphicsLayer {
                                    this.scaleX = scale
                                    this.scaleY = scale
                                    this.rotationY = rotationY
                                    this.cameraDistance = 15f * density
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            // Stronger = brighter and sharper (less blur), and
                            // fading out toward its far edge like a real
                            // reflection on glass. At 0 it's the original
                            // faint, evenly-blurred one.
                            val reflection = reflectionStrength.coerceIn(0f, 1f)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .offset(y = 460.dp)
                                    .graphicsLayer {
                                        rotationX = 180f
                                        alpha = lerp(0.12f, 0.5f, reflection) * (1f - (dropAnim.value / 1000f).coerceIn(0f, 1f))
                                        scaleY = 0.7f
                                        compositingStrategy = CompositingStrategy.Offscreen
                                    }
                                    .drawWithContent {
                                        drawContent()
                                        // Flipped by rotationX, so the local
                                        // top is the reflection's far end.
                                        drawRect(
                                            brush = Brush.verticalGradient(
                                                colors = listOf(Color.White.copy(alpha = 1f - reflection), Color.White)
                                            ),
                                            blendMode = BlendMode.DstIn
                                        )
                                    }
                                    .blur(lerp(8f, 1.5f, reflection).dp)
                            ) {
                                AsyncImage(
                                    model = albumItem.artUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            var cardBounds by remember { mutableStateOf(Rect.Zero) }
                            val isPlayable = abs(pageOffset) < 0.2f && showLabels && !isFadingOut
                            // Square corners, matching the square-edged
                            // reflection on the glass table below.
                            Card(
                                shape = RectangleShape,
                                elevation = CardDefaults.cardElevation(24.dp),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .border(1.dp, Color.White.copy(alpha = 0.1f), RectangleShape)
                                    .onGloballyPositioned { cardBounds = it.boundsInWindow() }
                                    .then(
                                        // The whole card is tappable-to-play
                                        // whenever the play button is showing
                                        // (not just its small hit target) -
                                        // Favorite/Remove buttons below still
                                        // win at their own exact positions,
                                        // since they're nested clickables
                                        // that consume the tap first.
                                        if (isPlayable) {
                                            Modifier.clickable {
                                                isFadingOut = true
                                                onPlayAlbum(albumItem, cardBounds)
                                            }
                                        } else Modifier
                                    )
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    AsyncImage(
                                        model = albumItem.artUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )

                                    if (isPlayable) {
                                        Surface(
                                            color = Color.Black.copy(alpha = 0.7f),
                                            shape = CircleShape,
                                            modifier = Modifier.size(110.dp)
                                                .clickable {
                                                    isFadingOut = true
                                                    onPlayAlbum(albumItem, cardBounds)
                                                }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Play",
                                                tint = Color.White,
                                                modifier = Modifier.padding(24.dp).fillMaxSize()
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                if (isFavoritesMode) pendingFavoriteDelete = albumItem
                                                else isBeingRemoved.value = true
                                            },
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(16.dp)
                                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Remove",
                                                tint = Color.White
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(45.dp))
                        
                        AnimatedVisibility(
                            visible = showLabels && abs(pageOffset) < 0.1f,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.width(450.dp)
                            ) {
                                Text(
                                    text = albumItem.albumTitle,
                                    color = Color.White,
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = albumItem.artist,
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 20.sp,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxSize().alpha(fadeAlpha), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val painter = painterResource(id = R.drawable.ic_no_history)
                    Image(
                        painter = painter,
                        contentDescription = null,
                        modifier = Modifier.size(220.dp).alpha(0.3f)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        "No Albums Found",
                        color = Color.Gray,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Light
                    )
                    Text(
                        "Please go to Settings and Refresh Favorites",
                        color = Color.Gray.copy(alpha = 0.5f),
                        fontSize = 18.sp
                    )
                }
            }
        }

        // Track cards along the bottom while music plays: Previous on the
        // left, and on the right Now Playing, which every so often slides
        // off to the right to make way for Next Up, then slides back. Each
        // can be hidden in Settings; with only one of the right-hand pair
        // left (or no next track known), that one just stays put.
        val hasPrevious = trackState.prevTrack != "None"
        val hasNext = trackState.nextTrack != "None"
        val showPreviousCard = trackState.isPlaying && hasPrevious && !hidePrevious
        val showNowPlayingCard = trackState.isPlaying && !hideNowPlaying
        val showNextUpCard = trackState.isPlaying && hasNext && !hideNextUp

        AnimatedVisibility(
            visible = showPreviousCard,
            enter = fadeIn(tween(600)),
            exit = fadeOut(tween(600)),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(24.dp)
                .alpha(fadeAlpha)
        ) {
            CoverFlowTrackCard(
                label = "PREVIOUS",
                title = trackState.prevTrack,
                subtitle = trackState.prevArtist,
                artUrl = trackState.prevArtUrl,
                onClick = { previousTrackDetails = trackState.previousTrackDetails() }
            )
        }

        if (showNowPlayingCard || showNextUpCard) {
            var nextUpTurn by remember { mutableStateOf(false) }
            // Restarts on every track change, so a new song always opens on
            // its own Now Playing card first.
            LaunchedEffect(showNowPlayingCard, showNextUpCard, trackState.title) {
                nextUpTurn = false
                if (showNowPlayingCard && showNextUpCard) {
                    while (true) {
                        delay(12_000)
                        nextUpTurn = true
                        delay(6_000)
                        nextUpTurn = false
                    }
                }
            }
            val showingNextUp = showNextUpCard && (nextUpTurn || !showNowPlayingCard)

            AnimatedContent(
                targetState = showingNextUp,
                transitionSpec = {
                    (slideInHorizontally(tween(700, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(700))) togetherWith
                        (slideOutHorizontally(tween(700, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(500)))
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
                    .alpha(fadeAlpha),
                label = "nowPlayingNextUp"
            ) { nextUp ->
                if (nextUp) {
                    CoverFlowTrackCard(
                        label = "NEXT UP",
                        title = trackState.nextTrack,
                        subtitle = trackState.nextArtist,
                        artUrl = trackState.nextArtUrl,
                        onClick = onWakeUp
                    )
                } else {
                    CoverFlowTrackCard(
                        label = "NOW PLAYING",
                        title = trackState.title,
                        subtitle = if (trackState.album.isNotBlank()) "${trackState.artist} • ${trackState.album}" else trackState.artist,
                        artUrl = trackState.albumArtUrl,
                        onClick = onWakeUp
                    )
                }
            }
        }

        AnimatedContent(
            targetState = clockAlignment,
            transitionSpec = { fadeIn(tween(1500)) togetherWith fadeOut(tween(1500)) },
            modifier = Modifier.fillMaxSize().padding(top = 20.dp, start = 60.dp, end = 60.dp).alpha(fadeAlpha),
            label = "clockMove"
        ) { alignment ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = alignment) {
                Column(
                    horizontalAlignment = when {
                        alignment.toString().contains("End") -> Alignment.End
                        alignment.toString().contains("Start") -> Alignment.Start
                        else -> Alignment.CenterHorizontally
                    }
                ) {
                    Text(
                        text = formatClockTime(currentTime, use24HourClock),
                        fontSize = 90.sp, fontWeight = FontWeight.ExtraLight, color = Color.White.copy(alpha = 0.5f)
                    )
                    Text(
                        text = String.format(Locale.getDefault(), "%tA, %tB %te", currentTime, currentTime, currentTime).uppercase(),
                        fontSize = 16.sp, color = Color.White.copy(alpha = 0.3f), letterSpacing = 2.sp
                    )
                }
            }
        }

        MetadataDebuggerOverlay(
            show = showDebugInfo,
            trackState = trackState,
            onClose = { showDebugInfo = false }
        )

        previousTrackDetails?.let { track ->
            TrackDetailsDialog(
                heading = "Previous Track",
                track = track,
                onDismiss = { previousTrackDetails = null }
            )
        }

        // Drawn in-tree rather than as an AlertDialog, since this screen
        // also runs inside SonosDreamService, which isn't an Activity.
        pendingFavoriteDelete?.let { item ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
                    // Swallows taps so they don't wake the screen; a tap
                    // outside the card cancels.
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { pendingFavoriteDelete = null })
                    }
            ) {
                Surface(
                    color = Color(0xFF1E1E1E),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .width(480.dp)
                        .pointerInput(Unit) { detectTapGestures { } }
                ) {
                    Column(modifier = Modifier.padding(28.dp)) {
                        Text(
                            "Delete from Sonos Favorites?",
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "\"${item.albumTitle}\" will be permanently removed from your Sonos " +
                                "Favorites, including in the Sonos app. This can't be undone here.",
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TextButton(onClick = { pendingFavoriteDelete = null }) {
                                Text("Cancel", color = Color.White)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = {
                                pendingFavoriteDelete = null
                                confirmedFavoriteDelete = item
                            }) {
                                Text("Delete", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

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

private fun lerp(start: Float, stop: Float, fraction: Float): Float {
    return (1 - fraction) * start + fraction * stop
}
