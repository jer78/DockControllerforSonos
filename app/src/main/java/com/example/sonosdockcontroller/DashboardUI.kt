package com.example.sonosdockcontroller

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
val dockScreenTransitionSpec: AnimatedContentTransitionScope<DockScreen>.() -> ContentTransform = {
    val spec = tween<IntOffset>(550, easing = FastOutSlowInEasing)
    if (targetState == DockScreen.NOW_PLAYING) {
        slideInVertically(animationSpec = spec) { fullHeight -> fullHeight } togetherWith
            slideOutVertically(animationSpec = spec) { fullHeight -> -fullHeight }
    } else {
        slideInVertically(animationSpec = spec) { fullHeight -> -fullHeight } togetherWith
            slideOutVertically(animationSpec = spec) { fullHeight -> fullHeight }
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
                
                Text("CURRENTLY PLAYING:", color = Color.Blue, fontWeight = FontWeight.Bold)
                Text("TITLE: ${trackState.title}", color = Color.Black)
                Text("ARTIST: ${trackState.artist}", color = Color.Black)
                Text("ALBUM: ${trackState.album}", color = Color.Black)
                
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color.Black.copy(alpha = 0.2f))
                
                Text("SYSTEM METADATA (XML):", color = Color.DarkGray, fontWeight = FontWeight.Bold)
                Text(trackState.rawMetadata, color = Color.DarkGray, fontSize = 12.sp)
                
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

@Composable
fun LargeVolumeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    isMuted: Boolean = false,
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

    val muteFlashTransition = rememberInfiniteTransition(label = "volumeMuteFlash")
    val muteFlashAlpha by muteFlashTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "muteFlashAlpha"
    )
    val fillColor = if (isMuted) SonosMuteGreen.copy(alpha = muteFlashAlpha) else Color.White
    val labelColorOnFill = if (isMuted) Color.White else Color.Black
    val labelColorOnTrack = if (isMuted) SonosMuteGreen.copy(alpha = muteFlashAlpha) else Color.White

    Box(
        modifier = modifier
            .height(80.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(40.dp))
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
                .background(fillColor)
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
                fontSize = 24.sp,
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
                fontSize = 24.sp,
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

// How long a peak mark sits still after being set, and how long it then
// takes to fade out - both purely cosmetic, tuned by eye.
private const val PEAK_HOLD_MS = 1200L
private const val PEAK_FADE_MS = 900L

@Composable
fun SpectrumAnalyzerView(
    bands: FloatArray,
    frequencyLabels: List<Int>,
    modifier: Modifier = Modifier
) {
    val bandCount = bands.size
    if (bandCount == 0) return

    // Bar heights: fast attack, slower decay so it doesn't look jittery.
    val smoothed = remember(bandCount) { FloatArray(bandCount) }
    // Per-band peak-hold: the height it's marking, and when it was last set.
    val peakHeights = remember(bandCount) { FloatArray(bandCount) }
    val peakSetAt = remember(bandCount) { LongArray(bandCount) }
    var frameTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(bandCount) {
        while (isActive) {
            val now = System.currentTimeMillis()
            for (i in 0 until bandCount) {
                val target = bands.getOrElse(i) { 0f }.coerceIn(0f, 1f)
                smoothed[i] = if (target > smoothed[i]) target else smoothed[i] * 0.80f + target * 0.20f
                if (smoothed[i] >= peakHeights[i]) {
                    peakHeights[i] = smoothed[i]
                    peakSetAt[i] = now
                } else if (now - peakSetAt[i] > PEAK_HOLD_MS) {
                    // Peak mark has sat long enough - let it drift back down.
                    peakHeights[i] = (peakHeights[i] - 0.03f).coerceAtLeast(smoothed[i])
                }
            }
            frameTime = now
            delay(33) // ~30fps, independent of the mic capture's own frame rate
        }
    }

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val gap = 4.dp.toPx()
            val barWidth = (size.width - gap * (bandCount - 1)) / bandCount
            val peakMarkHeight = 2.dp.toPx()

            for (i in 0 until bandCount) {
                val x = i * (barWidth + gap)
                val barH = smoothed[i] * size.height
                drawRect(
                    color = Color.Cyan.copy(alpha = 0.55f),
                    topLeft = Offset(x, size.height - barH),
                    size = Size(barWidth, barH)
                )

                val age = frameTime - peakSetAt[i]
                val fadeAlpha = if (age <= PEAK_HOLD_MS) {
                    1f
                } else {
                    (1f - (age - PEAK_HOLD_MS).toFloat() / PEAK_FADE_MS).coerceIn(0f, 1f)
                }
                if (fadeAlpha > 0f) {
                    val peakY = size.height - (peakHeights[i] * size.height)
                    drawRect(
                        color = Color.White.copy(alpha = fadeAlpha),
                        topLeft = Offset(x, (peakY - peakMarkHeight).coerceAtLeast(0f)),
                        size = Size(barWidth, peakMarkHeight)
                    )
                }
            }
        }

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

private fun formatFrequencyLabel(freqHz: Int): String {
    if (freqHz < 1000) return "$freqHz"
    val k = freqHz / 1000f
    return if (k == k.toInt().toFloat()) "${k.toInt()}k" else String.format(Locale.getDefault(), "%.1fk", k)
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
    onBrightnessChange: (Int) -> Unit,
    onEnterAmbient: () -> Unit,
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
    micPermissionGranted: Boolean = false
) {
    var showDebugInfo by remember { mutableStateOf(false) }
    var showGroupingModal by remember { mutableStateOf(false) }
    var showThermostat by remember { mutableStateOf(false) }
    var showLightDialog by remember { mutableStateOf(false) }

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { showDebugInfo = !showDebugInfo }
                )
            }
            .pointerInput(Unit) {
                // Swipe down anywhere on the background to jump to Cover
                // Flow, same as the button below - a descendant control
                // (volume slider, buttons, etc.) that starts its own drag/
                // click already consumes the touch, so this only fires for
                // swipes starting on empty background.
                val thresholdPx = 100.dp.toPx()
                var totalDrag = 0f
                detectVerticalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onVerticalDrag = { _, dragAmount -> totalDrag += dragAmount },
                    onDragEnd = {
                        if (totalDrag > thresholdPx) onEnterAmbient()
                    }
                )
            }
    ) {
        if (trackState.albumArtUrl.isNotBlank()) {
            AsyncImage(
                model = trackState.albumArtUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(60.dp)
                    .alpha(0.35f)
            )
        }

        if (showSpectrumAnalyzer && micPermissionGranted && trackState.isPlaying) {
            SpectrumAnalyzerView(
                bands = spectrumBands,
                frequencyLabels = spectrumAnalyzer.bandFrequencies,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.32f)
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

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LabeledIconButton(
                    icon = Icons.Default.SpeakerGroup,
                    label = "Speaker Groups",
                    onClick = { showGroupingModal = true }
                )

                LabeledIconButton(
                    icon = Icons.Default.BurstMode,
                    label = "Cover Flow",
                    onClick = onEnterAmbient
                )
            }
        }

        LabeledIconButton(
            icon = Icons.Default.Settings,
            label = "Settings",
            onClick = onOpenSettings,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 70.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Card(
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .onGloballyPositioned { onAlbumArtPositioned(it.boundsInWindow()) }
            ) {
                if (trackState.albumArtUrl.isNotBlank()) {
                    AsyncImage(
                        model = trackState.albumArtUrl,
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
            }

            Spacer(modifier = Modifier.width(48.dp))

            Column(
                modifier = Modifier.weight(1.2f),
                verticalArrangement = Arrangement.Center
            ) {
                if (isTvMode) {
                    Surface(
                        color = Color(0xFFE53935).copy(alpha = 0.8f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Tv, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "TV Input Active • HA Media Player",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    if (trackState.prevTrack != "None") {
                        Surface(
                            color = Color.White.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(12.dp)
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

                if (!isTvMode && trackState.nextTrack != "None") {
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
                }

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
                        
                        val muteIconFlash = rememberInfiniteTransition(label = "muteIconFlash")
                        val muteIconAlpha by muteIconFlash.animateFloat(
                            initialValue = 1f,
                            targetValue = 0.25f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(500, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "muteIconAlpha"
                        )
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
    }
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Speaker Groups", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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
                                    modifier = Modifier.weight(1f)
                                ) { Text("Join All") }
                            }
                            if (anyJoined) {
                                OutlinedButton(
                                    onClick = {
                                        speakerGroups.filter { displayedJoin(it) }
                                            .forEach { setJoin(it.name, false) }
                                    },
                                    modifier = Modifier.weight(1f)
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
                                .background(if (isJoined) Color.Blue.copy(alpha = 0.1f) else Color.Transparent)
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(id = sonosModelIconRes(group.model)),
                                    contentDescription = null,
                                    tint = if (isJoined) Color.Blue else Color.Gray,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(group.name, fontWeight = FontWeight.SemiBold)
                                    Text(if (isJoined) "Joined" else "Available", fontSize = 12.sp, color = if (isJoined) Color.Blue else Color.Gray)
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
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Done") }
        }
    )
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

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Color(0xFF161616),
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.width(420.dp)
        ) {
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

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Color(0xFF161616),
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.width(380.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
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

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
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

                Spacer(Modifier.height(16.dp))

                Text("Brightness", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Brightness4,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp)
                    )
                    Slider(
                        value = localBrightness,
                        onValueChange = {
                            localBrightness = it
                            scheduleBrightness(it.toInt())
                        },
                        valueRange = 0f..255f,
                        enabled = haState.lightOn,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.Yellow,
                            activeTrackColor = Color.Yellow,
                            inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                        )
                    )
                    Icon(
                        Icons.Default.Brightness7,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    "${(localBrightness / 255f * 100).roundToInt()}%",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.End)
                )

                if (supportsRgb) {
                    Spacer(Modifier.height(16.dp))
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
                } else if (supportsColorTemp) {
                    Spacer(Modifier.height(16.dp))
                    Text("Color Temperature", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsModal(
    discoveredSpeakers: List<String>,
    settingsManager: SettingsManager,
    refreshStatus: String,
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
    var showSpectrumAnalyzer by remember { mutableStateOf(false) }

    var fAlbums by remember { mutableStateOf(true) }
    var fPlaylists by remember { mutableStateOf(true) }
    var fRadio by remember { mutableStateOf(true) }

    var showClearHistoryConfirm by remember { mutableStateOf(false) }

    val haManager = remember { HomeAssistantManager() }
    var lightEntities by remember { mutableStateOf<List<String>>(emptyList()) }
    var climateEntities by remember { mutableStateOf<List<String>>(emptyList()) }
    var mediaPlayerEntities by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        selectedSpeaker = settingsManager.sonosSpeaker.first()
        haUrl = settingsManager.haUrl.first()
        haToken = settingsManager.haToken.first()
        selectedLight = settingsManager.selectedLight.first()
        selectedClimate = settingsManager.selectedClimate.first()
        selectedHaMediaPlayer = settingsManager.selectedHaMediaPlayer.first()
        coverFlowMode = settingsManager.coverFlowMode.first()
        fAlbums = settingsManager.filterAlbums.first()
        fPlaylists = settingsManager.filterPlaylists.first()
        fRadio = settingsManager.filterRadio.first()
        showSpectrumAnalyzer = settingsManager.showSpectrumAnalyzer.first()

        // Pull every light/climate/media_player entity HA knows about, once
        // a connection is available, so the fields below can autocomplete.
        if (haUrl.isNotBlank() && haToken.isNotBlank()) {
            haManager.fetchEntities(haUrl, haToken) { all ->
                lightEntities = all.filter { it.startsWith("light.") }.sorted()
                climateEntities = all.filter { it.startsWith("climate.") }.sorted()
                mediaPlayerEntities = all.filter { it.startsWith("media_player.") }.sorted()
            }
        }
    }

    val modes = listOf(
        "history" to "Mode A: History of Favorited Albums",
        "favorites" to "Mode B: Albums, Playlists & Radio"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.94f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Dashboard Configuration", fontWeight = FontWeight.Bold) },
        text = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // LEFT COLUMN: Sonos playback source
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

                    HorizontalDivider()

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

                    HorizontalDivider()

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

                    if (coverFlowMode == "history") {
                        Text(
                            "Albums need to be favorited in the Sonos app. This mode just captures the history of when your favorited albums are played.",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                        OutlinedButton(
                            onClick = { showClearHistoryConfirm = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = albumHistoryCount > 0,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Clear History ($albumHistoryCount)")
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
                            onClick = { onRefreshFavorites(fAlbums, fPlaylists, fRadio) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Refresh Favorites")
                        }

                        Text(
                            text = "Status: $refreshStatus",
                            fontSize = 12.sp,
                            color = if (refreshStatus.contains("Error")) Color.Red else Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(Color.LightGray.copy(alpha = 0.5f))
                )

                // RIGHT COLUMN: Home Assistant integration
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Home Assistant Integration:", fontWeight = FontWeight.SemiBold)

                    OutlinedTextField(
                        value = haUrl,
                        onValueChange = { haUrl = it },
                        label = { Text("Server URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = haToken,
                        onValueChange = { haToken = it },
                        label = { Text("Long-Lived Access Token") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

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

                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.align(Alignment.End)
                    )
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
                        fRadio
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
        AlertDialog(
            onDismissRequest = { showClearHistoryConfirm = false },
            title = { Text("Clear History?") },
            text = { Text("This permanently removes all $albumHistoryCount saved history entries. This can't be undone.") },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AmbientClockScreen(
    albumHistory: List<AlbumHistoryItem>,
    trackState: TrackState,
    onPlayAlbum: (AlbumHistoryItem, Rect) -> Unit,
    onRemoveAlbum: (String) -> Unit,
    onWakeUp: () -> Unit
) {
    var isFadingOut by remember { mutableStateOf(false) }
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
        initialPage = internalPageCount / 2,
        pageCount = { internalPageCount }
    )

    val isDragged by pagerState.interactionSource.collectIsDraggedAsState()
    var isUserInteracting by remember { mutableStateOf(false) }
    var showLabels by remember { mutableStateOf(false) }

    LaunchedEffect(isUserInteracting, isDragged) {
        if (isUserInteracting || isDragged) {
            showLabels = true
        } else {
            delay(5000) 
            showLabels = false
        }
    }

    LaunchedEffect(albumHistory, isUserInteracting, isDragged) {
        if (albumHistory.isNotEmpty()) {
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
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onWakeUp() },
                    onLongPress = { showDebugInfo = !showDebugInfo }
                )
            }
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.35f)
                .alpha(fadeAlpha)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.05f))
                    )
                )
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
                            onRemoveAlbum(albumItem.artUrl)
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
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .offset(y = 460.dp)
                                    .graphicsLayer {
                                        rotationX = 180f
                                        alpha = 0.12f * (1f - (dropAnim.value / 1000f).coerceIn(0f, 1f))
                                        scaleY = 0.7f
                                    }
                                    .blur(8.dp)
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
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                elevation = CardDefaults.cardElevation(24.dp),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
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
                                            onClick = { isBeingRemoved.value = true },
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

        if (trackState.isPlaying) {
             Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
                    .width(360.dp)
                    .alpha(fadeAlpha)
                    .clickable { onWakeUp() }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "NOW PLAYING",
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (trackState.albumArtUrl.isNotBlank()) {
                            AsyncImage(
                                model = trackState.albumArtUrl,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                        }
                        Column {
                            Text(
                                text = trackState.title,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (trackState.album.isNotBlank()) "${trackState.artist} • ${trackState.album}" else trackState.artist,
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
                        text = String.format(Locale.getDefault(), "%02d:%02d", currentTime.get(Calendar.HOUR_OF_DAY), currentTime.get(Calendar.MINUTE)),
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
    }
}

@Composable
fun TvAmbientScreen(
    trackState: TrackState,
    onWakeUp: () -> Unit
) {
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
                if (trackState.albumArtUrl.isNotBlank()) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(20.dp),
                        modifier = Modifier.size(300.dp)
                    ) {
                        AsyncImage(
                            model = trackState.albumArtUrl,
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
            }
        }
    }
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float {
    return (1 - fraction) * start + fraction * stop
}
