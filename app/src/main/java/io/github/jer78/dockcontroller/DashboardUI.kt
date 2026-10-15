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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

// Shared, direction-aware transition for switching between the top-level
// screens (Now Playing, Cover Flow, TV Ambient) - used by both MainActivity
// and SonosDreamService so the two hosts stay in sync. Leaving Now Playing
// for a screensaver drops down (new screen enters from above, old one falls
// away below); coming back to Now Playing reverses that (old screensaver
// rises away above, Now Playing rises into place from below).
//
// Exception: Cover Flow reached by a sideways swipe on Now Playing slides in
// from the side the finger came from, or from the left for the Cover Flow
// button (and back out the same way) - `slide`
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
                val manager = remember { SonosDashboardManager.getInstance(context) }
                val recentErrors by manager.recentErrors.collectAsState()
                val sonosInstant by manager.instantUpdates.collectAsState()
                Text("INSTANT UPDATES:", color = Color.Blue, fontWeight = FontWeight.Bold)
                Text(
                    "Sonos: ${if (sonosInstant) "on (speaker events arriving)" else "off - polling every few seconds"}",
                    color = Color.Black
                )
                Text(
                    "Home Assistant: ${if (manager.haInstantUpdates) "on (WebSocket connected)" else "off - polling every few seconds"}",
                    color = Color.Black
                )

                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color.Black.copy(alpha = 0.2f))

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
internal val SonosMuteGreen = Color(0xFF4CAF50)

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
internal fun Modifier.debugToggleHoldGesture(onTrigger: () -> Unit): Modifier = pointerInput(Unit) {
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
    // Shown before the percentage, e.g. "Group · " when the slider drives
    // a whole group of rooms.
    labelPrefix: String = "",
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
                text = "$labelPrefix${localValue.roundToInt()}%",
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
                text = "$labelPrefix${localValue.roundToInt()}%",
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

internal fun formatScreensaverDelay(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return when {
        minutes == 0 -> "${seconds}s"
        seconds == 0 -> "${minutes}m"
        else -> "${minutes}m ${seconds}s"
    }
}

internal fun formatTrackTime(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}

// Playback progress bar you can tap or drag to seek. Thin bar, but a 32dp
// tall touch area so it's easy to hit. While dragging, the bar follows the
// finger and onScrub reports the previewed position (null once released) so
// the caller's time label can follow along; the seek itself is only sent on
// release, rather than flooding the speaker with one per frame.
@Composable
fun SeekBar(
    positionSeconds: Int,
    durationSeconds: Int,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onScrub: (Int?) -> Unit = {},
    barHeight: Dp = 6.dp,
    color: Color = Color.White.copy(alpha = 0.8f)
) {
    var width by remember { mutableIntStateOf(0) }
    var scrubSeconds by remember { mutableStateOf<Int?>(null) }
    fun secondsAt(x: Float): Int =
        if (width <= 0) 0 else ((x / width).coerceIn(0f, 1f) * durationSeconds).roundToInt()
    fun scrubTo(seconds: Int?) {
        scrubSeconds = seconds
        onScrub(seconds)
    }

    val shown = scrubSeconds ?: positionSeconds
    val fraction = if (durationSeconds > 0) (shown.toFloat() / durationSeconds).coerceIn(0f, 1f) else 0f
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .onSizeChanged { width = it.width }
            .pointerInput(durationSeconds) {
                detectTapGestures { offset -> onSeek(secondsAt(offset.x)) }
            }
            .pointerInput(durationSeconds) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> scrubTo(secondsAt(offset.x)) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        scrubTo(secondsAt(change.position.x))
                    },
                    onDragEnd = {
                        scrubSeconds?.let(onSeek)
                        scrubTo(null)
                    },
                    onDragCancel = { scrubTo(null) }
                )
            }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(RoundedCornerShape(barHeight / 2))
                .background(Color.White.copy(alpha = 0.2f))
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(color)
            )
        }
        // Thumb, only while dragging, so the resting bar stays minimal.
        if (scrubSeconds != null) {
            val density = LocalDensity.current
            val thumb = 18.dp
            Box(
                Modifier
                    .offset { IntOffset((fraction * width - with(density) { thumb.toPx() } / 2).roundToInt(), 0) }
                    .size(thumb)
                    .background(Color.White, CircleShape)
            )
        }
    }
}

// The 2s poll only updates positionSeconds in discrete jumps, which would
// make the displayed clock visibly stutter - resync to the polled value on
// every change, then tick up locally once a second in between polls so it
// reads like a smooth, live counter.
@Composable
internal fun rememberInterpolatedPosition(trackState: TrackState): Int {
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
internal suspend fun extractAlbumPalette(context: Context, imageUrl: String): List<Color> {
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
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit
) {
    // Inside the screensaver: drawn in the dream's own window, which blurs
    // what's behind it itself (see InWindowDialogs.kt).
    if (LocalInWindowDialogHost.current != null) {
        DockDialog(onDismissRequest = onDismissRequest, properties = properties) {
            CompositionLocalProvider(LocalGlassBlurActive provides inWindowBlurSupported) {
                content()
            }
        }
        return
    }

    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
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
