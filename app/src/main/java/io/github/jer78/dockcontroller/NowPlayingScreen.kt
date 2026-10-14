package io.github.jer78.dockcontroller

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlin.math.abs

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
    onCycleRepeat: () -> Unit = {},
    onSeek: (Int) -> Unit = {},
    onEnterAmbient: (ScreenSlide) -> Unit,
    albumHistory: List<AlbumHistoryItem>,
    onOpenSettings: () -> Unit,
    speakerGroups: List<SonosSpeakerGroup>,
    onToggleJoin: (String, Boolean) -> Unit,
    onAlbumArtPositioned: (Rect) -> Unit = {},
    onHaCommand: (HaCommand) -> Unit = {},
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
    // Entity id of the Home Assistant widget whose pop-up is open.
    var openHaEntity by remember { mutableStateOf<String?>(null) }
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
            HaWidgetRow(haState = haState, onOpen = { openHaEntity = it })
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
                // Same slide as a swipe right, so the button and the
                // sideways swipes feel like one gesture.
                onClick = { onEnterAmbient(ScreenSlide.FROM_LEFT) }
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
                    var scrubSeconds by remember { mutableStateOf<Int?>(null) }
                    val remainingSeconds = (trackState.durationSeconds - (scrubSeconds ?: displayedSeconds)).coerceAtLeast(0)
                    Spacer(modifier = Modifier.height(7.dp))
                    SeekBar(
                        positionSeconds = displayedSeconds,
                        durationSeconds = trackState.durationSeconds,
                        onSeek = onSeek,
                        onScrub = { scrubSeconds = it }
                    )
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
                    var scrubSeconds by remember { mutableStateOf<Int?>(null) }
                    SeekBar(
                        positionSeconds = displayedSeconds,
                        durationSeconds = trackState.durationSeconds,
                        onSeek = onSeek,
                        onScrub = { scrubSeconds = it },
                        barHeight = 4.dp,
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        text = "${formatTrackTime(scrubSeconds ?: displayedSeconds)} / ${formatTrackTime(trackState.durationSeconds)}",
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.55f)
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

                        // Same availability as shuffle. Off -> All -> One;
                        // filled white while on, with the "1" icon for One.
                        val repeatOn = trackState.repeatMode != "OFF"
                        IconButton(
                            onClick = onCycleRepeat,
                            modifier = Modifier
                                .size(56.dp)
                                .background(
                                    if (repeatOn) Color.White else Color.White.copy(alpha = 0.2f),
                                    CircleShape
                                )
                        ) {
                            Icon(
                                if (trackState.repeatMode == "ONE") Icons.Default.RepeatOne else Icons.Default.Repeat,
                                when (trackState.repeatMode) {
                                    "ALL" -> "Repeat all"
                                    "ONE" -> "Repeat one"
                                    else -> "Repeat off"
                                },
                                tint = if (repeatOn) Color.Black else Color.White
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
                            // Grouped: volume and mute are the whole group's
                            // (per-room levels live in Speaker Groups).
                            labelPrefix = if (trackState.isGrouped) "Group · " else "",
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

        openHaEntity?.let { haState.entities[it] }?.let { entity ->
            HaWidgetDialog(
                entity = entity,
                onHaCommand = onHaCommand,
                onDismiss = { openHaEntity = null }
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
