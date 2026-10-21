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
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.util.Calendar
import kotlinx.coroutines.delay
import kotlin.math.abs

// Taps on the TV-mode artwork closer together than this count towards the
// triple tap that shows the TV show detection debug.
private const val TV_DEBUG_TAP_WINDOW_MS = 500L

// How long TV mode's clock shows the time, then the finish time, in turn.
private const val TV_CLOCK_SWAP_MS = 6_000L
private val TvClockEndColor = Color(0xFFFFC94D)

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
    // The song picker's queue, and playing a track from it by position.
    onLoadQueue: suspend () -> SonosQueue? = { null },
    onPlayQueueTrack: (Int) -> Unit = {},
    onSeek: (Int) -> Unit = {},
    onEnterAmbient: (ScreenSlide) -> Unit,
    albumHistory: List<AlbumHistoryItem>,
    onOpenSettings: () -> Unit,
    // Cover Flow's saved view, so its button names the grid when that's
    // what it opens.
    coverFlowGridView: Boolean = false,
    speakerGroups: List<SonosSpeakerGroup>,
    onToggleJoin: (String, Boolean) -> Unit,
    onAlbumArtPositioned: (Rect) -> Unit = {},
    onHaCommand: (HaCommand) -> Unit = {},
    // For lock and alarm actions, whose pop-ups wait for Home Assistant's answer.
    onHaCommandWithResult: (HaCommand, (HaServiceResult) -> Unit) -> Unit = { command, onResult ->
        onHaCommand(command)
        onResult(HaServiceResult.Success)
    },
    showSpectrumAnalyzer: Boolean = false,
    micPermissionGranted: Boolean = false,
    onRequestMicPermission: () -> Unit = {},
    spectrumColorScheme: SpectrumColorScheme = SpectrumColorScheme.RAINBOW,
    spectrumHeightPercent: Float = 1f,
    // Settings > Now Playing > Background Blur, in dp. TV's is
    // SettingsManager.BACKGROUND_BLUR_SAME_AS_MUSIC to follow music's.
    musicBackgroundBlur: Float = SettingsManager.BACKGROUND_BLUR_DEFAULT,
    tvBackgroundBlur: Float = SettingsManager.BACKGROUND_BLUR_SAME_AS_MUSIC,
    spectrumConfineToAlbumArt: Boolean = false,
    audioSettings: SonosAudioSettings = SonosAudioSettings(),
    // Settings > Now Playing > Track Info.
    hidePrevious: Boolean = false,
    hideNextUp: Boolean = false,
    // Settings > Now Playing > Clock.
    clockMode: NowPlayingClockMode = NowPlayingClockMode.OFF,
    use24HourClock: Boolean = true,
    // Settings > Now Playing > Show Ends At Time: TV mode's clock taking
    // turns with when the show finishes.
    showEndsAt: Boolean = true,
    onToggleNightMode: () -> Unit = {},
    onToggleDialogMode: () -> Unit = {},
    onSetBass: (Int) -> Unit = {},
    onSetTreble: (Int) -> Unit = {},
    onSetLoudness: (Boolean) -> Unit = {},
    onToggleSubEnabled: () -> Unit = {},
    // The widgets shown, in their new order, after a drag to reorder them.
    onReorderHaWidgets: (List<HaWidgetSlot>) -> Unit = {},
    // Settings > Now Playing > Show Cover Flow Between Songs: called once an
    // album has played SWAP_WHILE_PLAYING_DELAY_MS here untouched.
    swapWhilePlaying: Boolean = false,
    onSwapWhilePlaying: () -> Unit = {},
    // Shuffle/crossfade turned off for a live album (see LiveAlbumNotice).
    liveAlbumNotice: LiveAlbumNotice? = null,
    onRestoreLiveAlbumSettings: () -> Unit = {},
    onDismissLiveAlbumNotice: () -> Unit = {},
    // A song picked from the queue that Sonos skipped (its title).
    skippedSongNotice: String? = null,
    onDismissSkippedSongNotice: () -> Unit = {},
    // Replay in the Previous track's details (see SonosDashboardManager.replayTrack).
    canReplayTrack: suspend (TrackDetails) -> Boolean = { false },
    onReplayTrack: (TrackDetails) -> Unit = {},
    // Speaker Groups or Sound Settings opening - re-reads the rooms and
    // sound settings, which are otherwise only refreshed every few minutes.
    onRefreshSpeakerSettings: () -> Unit = {},
    // Room on the left of the bottom-left notices for an overlay the system
    // draws there (the Pixel Tablet's Home button over the screensaver).
    bottomStartInset: Dp = 0.dp
) {
    var showDebugInfo by remember { mutableStateOf(false) }
    // A widget being held/dragged - the 3s debug hold mustn't fire then.
    var reorderingWidget by remember { mutableStateOf(false) }
    // What TV show detection sees, over the artwork: a triple tap on the
    // artwork in TV mode shows it, and a single tap hides it again.
    var showTvDebug by remember { mutableStateOf(false) }
    LaunchedEffect(isTvMode) { if (!isTvMode) showTvDebug = false }
    var showGroupingModal by remember { mutableStateOf(false) }
    // Entity id of the Home Assistant widget whose pop-up is open.
    var openHaEntity by remember { mutableStateOf<String?>(null) }
    var showSoundSettings by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var previousTrackDetails by remember { mutableStateOf<TrackDetails?>(null) }

    val spectrumAnalyzer = remember { SpectrumAnalyzer() }
    val spectrumBands by spectrumAnalyzer.bands.collectAsState()
    DisposableEffect(Unit) {
        onDispose { spectrumAnalyzer.stop() }
    }
    // Not while the screen's off or the app's in the background - nobody
    // can see it, and Android hands a background app silence anyway.
    val visible = isScreenVisible()
    LaunchedEffect(showSpectrumAnalyzer, micPermissionGranted, trackState.isPlaying, visible) {
        if (showSpectrumAnalyzer && micPermissionGranted && trackState.isPlaying && visible) {
            spectrumAnalyzer.start(this)
        } else {
            spectrumAnalyzer.stop()
        }
    }

    val context = LocalContext.current
    val artworkUrl = TvFallbackArt.rememberArtworkUrl(isTvMode, trackState.albumArtUrl)
    var albumPalette by remember { mutableStateOf<List<Color>>(emptyList()) }
    // Read as soon as the art changes, not just when the spectrum wants it,
    // so the mute color below is ready the moment mute is pressed - reading
    // it then flashed the fallback green first. Only the "Album Art"
    // spectrum scheme uses it, so other schemes are unaffected.
    LaunchedEffect(artworkUrl) {
        albumPalette = extractAlbumPalette(context, artworkUrl)
    }
    // A fresh pick each time it's muted, or the art changes while muted.
    val muteColor = remember(trackState.isMuted, albumPalette) { pickMuteColor(albumPalette) }

    // Only queued albums (not radio or TV), and never with a panel open. A
    // touch or a new song starts the wait over.
    var touches by remember { mutableIntStateOf(0) }
    // Every minute untouched, the background re-frames and everything on
    // it glides a little (see AmbientDrift) - TV and music alike.
    val drift = rememberAmbientDrift(touches)
    // TV mode, until the TV screensaver takes over (which can be many
    // minutes): every CAST_TURN_INTERVAL_MS untouched, the show's cast (see
    // TvShowCast) takes a turn in place of what's on, a page or two of it,
    // then what's on comes back. A touch brings it straight back.
    var castPage by remember { mutableStateOf<List<CastMember>?>(null) }
    val currentCast by rememberUpdatedState(trackState.tvCast)
    LaunchedEffect(touches, isTvMode) {
        castPage = null
        if (!isTvMode) return@LaunchedEffect
        var nextPage = 0
        while (true) {
            delay(CAST_TURN_INTERVAL_MS)
            val pages = currentCast.chunked(CAST_PER_PAGE)
            if (pages.isEmpty()) continue
            repeat(minOf(CAST_PAGES_PER_TURN, pages.size)) {
                castPage = pages[nextPage++ % pages.size]
                delay(CAST_PAGE_MS)
            }
            castPage = null
        }
    }
    val castAlpha by animateFloatAsState(
        targetValue = if (castPage != null) 1f else 0f,
        animationSpec = tween(1500, easing = FastOutSlowInEasing),
        label = "tvCastTurn"
    )
    // Kept while it fades away.
    var lastCastPage by remember { mutableStateOf<List<CastMember>?>(null) }
    if (castPage != null) lastCastPage = castPage
    val panelOpen = showDebugInfo || showTvDebug || showGroupingModal || openHaEntity != null ||
        showSoundSettings || showQueue || previousTrackDetails != null || reorderingWidget
    val albumPlaying = swapWhilePlaying && !isTvMode && trackState.isPlaying && trackState.shuffleAvailable
    val currentOnSwapWhilePlaying by rememberUpdatedState(onSwapWhilePlaying)
    LaunchedEffect(albumPlaying, panelOpen, touches, trackState.title) {
        if (albumPlaying && !panelOpen) {
            delay(SWAP_WHILE_PLAYING_DELAY_MS)
            currentOnSwapWhilePlaying()
        }
    }

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
            .debugToggleHoldGesture(isSuppressed = { reorderingWidget }) { showDebugInfo = !showDebugInfo }
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
        // A recognised TV show's own background image, when TVmaze or TMDB
        // has one, replaces the artwork - blurred the same way.
        val backgroundBlur = (if (isTvMode && tvBackgroundBlur >= 0f) tvBackgroundBlur else musicBackgroundBlur).dp
        val backgroundUrl = trackState.tvShowLogo?.backgroundUrl?.takeIf { isTvMode } ?: artworkUrl
        if (backgroundUrl.isNotBlank()) {
            AsyncImage(
                model = backgroundUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(drift.backgroundModifier)
                    .then(if (backgroundBlur > 0.dp) Modifier.blur(backgroundBlur) else Modifier)
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
                .then(drift.contentModifier)
                .padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            HaWidgetRow(
                haState = haState,
                onOpen = { openHaEntity = it },
                onHaCommand = onHaCommand,
                onReorder = onReorderHaWidgets,
                onReorderActiveChange = { reorderingWidget = it }
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .then(drift.contentModifier)
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
                icon = if (coverFlowGridView) Icons.Default.GridView else Icons.Default.BurstMode,
                label = if (coverFlowGridView) "Cover Grid" else "Cover Flow",
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
                .then(drift.contentModifier)
                // Fades out while the cast takes its turn (TV mode).
                .graphicsLayer { alpha = 1f - castAlpha }
                .padding(horizontal = 48.dp, vertical = 70.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                // Square for album art; in TV mode it slowly letterboxes to
                // 16:9 (same width, top and bottom closing in), like a movie
                // starting - and opens back up when music returns. A show's
                // poster takes the poster's shape instead, as tall as the
                // room left beside the clock and seek bar allows (narrower
                // and centred).
                val posterAspect = trackState.tvShowLogo
                    ?.takeIf { isTvMode && it.kind == TvShowLogo.Kind.POSTER }?.aspectRatio
                val artAspectRatio by animateFloatAsState(
                    targetValue = posterAspect ?: if (isTvMode) 16f / 9f else 1f,
                    animationSpec = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
                    label = "artAspectRatio"
                )
                // Music mode: above the artwork. TV mode puts it above the
                // show's title instead (below), clear of the widget labels -
                // but keeps its room here, invisible, so the artwork stays
                // the same size and clear of the widgets.
                // The artwork card's width, for TV mode's seek bar.
                var artWidthPx by remember { mutableIntStateOf(0) }
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
                            .alpha(if (isTvMode) 0f else 1f)
                    )
                }
                Card(
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(16.dp),
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        // Measured after the clock and seek bar, so it only
                        // gets the height they leave; aspectRatio then fits
                        // the full width, or narrows to fit that height.
                        .weight(1f, fill = false)
                        .aspectRatio(artAspectRatio)
                        .onSizeChanged { artWidthPx = it.width }
                        .onGloballyPositioned { onAlbumArtPositioned(it.boundsInWindow()) }
                ) {
                    // Wrapped in a Box (rather than leaving Card's default Column
                    // content scope) so the confine-to-album-art spectrum overlay
                    // below can stack on top of the art instead of being laid out
                    // as a second, height-starved Column child underneath it.
                    var tvDebugTaps by remember { mutableIntStateOf(0) }
                    var lastTvDebugTapAt by remember { mutableLongStateOf(0L) }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(isTvMode) {
                                if (!isTvMode) return@pointerInput
                                detectTapGestures {
                                    val now = System.currentTimeMillis()
                                    if (showTvDebug) {
                                        showTvDebug = false
                                        tvDebugTaps = 0
                                    } else {
                                        tvDebugTaps = if (now - lastTvDebugTapAt <= TV_DEBUG_TAP_WINDOW_MS) tvDebugTaps + 1 else 1
                                        if (tvDebugTaps >= 3) {
                                            showTvDebug = true
                                            tvDebugTaps = 0
                                        }
                                    }
                                    lastTvDebugTapAt = now
                                }
                            }
                    ) {
                        val showLogo = trackState.tvShowLogo
                        if (isTvMode && showLogo != null) {
                            TvShowLogoArt(logo = showLogo, artworkUrl = artworkUrl, showName = trackState.seriesTitle)
                        } else if (artworkUrl.isNotBlank()) {
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

                        // What TV show detection sees (see showTvDebug).
                        if (showTvDebug && isTvMode) {
                            val detected = detectTvShowName(trackState.title, trackState.seriesTitle, trackState.artist, trackState.album)
                            val logoSource = when {
                                detected == null -> "-"
                                showLogo == null -> "none (yet, or none found)"
                                else -> when (showLogo.kind) {
                                    TvShowLogo.Kind.TRANSPARENT_LOGO -> "TMDB"
                                    TvShowLogo.Kind.POSTER -> "TVmaze poster"
                                }
                            }
                            Text(
                                text = "title: ${trackState.title}\n" +
                                    "artist: ${trackState.artist}\n" +
                                    "series: ${trackState.seriesTitle.ifBlank { "(none)" }}\n" +
                                    "TV show: ${detected ?: "NOT DETECTED"}\n" +
                                    "logo: $logoSource\n" +
                                    "app: ${trackState.appName.ifBlank { "(none)" }}\n" +
                                    "app ID: ${trackState.appId.ifBlank { "(none)" }}\n" +
                                    "content ID: ${trackState.contentId.ifBlank { "(none)" }}\n" +
                                    "content type: ${trackState.contentType.ifBlank { "(none)" }}",
                                color = Color.White,
                                fontSize = 11.sp,
                                lineHeight = 13.sp,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(6.dp)
                                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )
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
                    // Only as wide as the artwork (a poster is narrower than
                    // the column), centred under it.
                    val artWidth = with(LocalDensity.current) { artWidthPx.toDp() }
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .then(if (artWidthPx > 0) Modifier.width(artWidth) else Modifier.fillMaxWidth())
                    ) {
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
            }

            Spacer(modifier = Modifier.width(48.dp))

            Column(
                modifier = Modifier.weight(1.2f),
                verticalArrangement = Arrangement.Center
            ) {
                // TV mode's clock, above the title and beside the artwork.
                if (isTvMode && clockMode.showsClock(isTvMode)) {
                    TvClock(trackState, use24HourClock, showEndsAt, modifier = Modifier.padding(bottom = 4.dp))
                }
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

                // Too long to fit, the title (and the line under it) scroll
                // across now and then rather than being cut off.
                Text(
                    text = trackState.title,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.nowPlayingMarquee()
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
                        modifier = Modifier.weight(1f, fill = false).nowPlayingMarquee()
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
                    // Same glass buttons as Speaker Groups and Sound
                    // Settings below, with Play/Pause the largest.
                    GlassIconButton(
                        icon = Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        onClick = { onCommand("previous") },
                        size = 56.dp,
                        iconSize = 28.dp
                    )

                    GlassIconButton(
                        icon = if (trackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (trackState.isPlaying) "Pause" else "Play",
                        onClick = { onCommand(if (trackState.isPlaying) "pause" else "play") },
                        size = 72.dp,
                        iconSize = 40.dp
                    )

                    GlassIconButton(
                        icon = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        onClick = { onCommand("next") },
                        size = 56.dp,
                        iconSize = 28.dp
                    )

                    // Only the Sonos queue can be shuffled, so no button for
                    // radio, line-in, TV etc. Filled white while on.
                    if (!isTvMode && trackState.shuffleAvailable) {
                        GlassIconButton(
                            icon = Icons.Default.Shuffle,
                            contentDescription = if (trackState.isShuffled) "Shuffle on" else "Shuffle off",
                            onClick = onToggleShuffle,
                            size = 56.dp,
                            active = trackState.isShuffled
                        )

                        // Same availability as shuffle. Off -> All -> One;
                        // filled white while on, with the "1" icon for One.
                        val repeatOn = trackState.repeatMode != "OFF"
                        GlassIconButton(
                            icon = if (trackState.repeatMode == "ONE") Icons.Default.RepeatOne else Icons.Default.Repeat,
                            contentDescription = when (trackState.repeatMode) {
                                "ALL" -> "Repeat all"
                                "ONE" -> "Repeat one"
                                else -> "Repeat off"
                            },
                            onClick = onCycleRepeat,
                            size = 56.dp,
                            active = repeatOn
                        )

                        // The queue's songs, to pick one to play.
                        GlassIconButton(
                            icon = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "Song list",
                            onClick = { showQueue = true },
                            size = 56.dp
                        )
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

                // Grouped, the main slider is the whole group's volume, so
                // every room - the main (coordinator) speaker first - gets
                // its own slider underneath.
                val roomSliders = if (trackState.groupMembers.size > 1) {
                    trackState.groupMembers.sortedByDescending { it.isCoordinator }
                } else emptyList()
                
                Column(
                    modifier = Modifier.fillMaxWidth(0.9f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Grouped or not, the main slider keeps the same width -
                    // its "Group" prefix says it's the whole group's volume.
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                            tint = if (trackState.isMuted) muteColor.copy(alpha = muteIconAlpha) else Color.White,
                            modifier = Modifier
                                .size(32.dp)
                                .background(
                                    if (trackState.isMuted) muteColor.copy(alpha = 0.2f * muteIconAlpha) else Color.Transparent,
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
                            muteColor = muteColor,
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
                            onClick = {
                                showGroupingModal = true
                                onRefreshSpeakerSettings()
                            }
                        )

                        if (hasSoundSettings) {
                            Spacer(modifier = Modifier.width(16.dp))

                            GlassIconButton(
                                icon = Icons.Default.Tune,
                                contentDescription = "Sound Settings",
                                onClick = {
                                    showSoundSettings = true
                                    onRefreshSpeakerSettings()
                                }
                            )
                        }
                    }

                    roomSliders.forEach { member ->
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
                onHaCommandWithResult = onHaCommandWithResult,
                onDismiss = { openHaEntity = null }
            )
        }

        previousTrackDetails?.let { track ->
            TrackDetailsDialog(
                heading = "Previous Track",
                track = track,
                onDismiss = { previousTrackDetails = null },
                canReplay = canReplayTrack,
                onReplay = onReplayTrack
            )
        }

        // The cast's turn (TV mode), where what's on was.
        val shownCastPage = lastCastPage
        if (castAlpha > 0f && shownCastPage != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .then(drift.contentModifier)
                    .graphicsLayer { alpha = castAlpha }
            ) {
                CastPage(
                    show = detectTvShowName(trackState.title, trackState.seriesTitle, trackState.artist, trackState.album)
                        ?: trackState.title,
                    cast = shownCastPage
                )
            }
        }

        // A live album: what the app turned off for it, and a button to
        // turn it back on. Kept while it animates away.
        // Below it, a song picked from the queue that Sonos skipped.
        var lastLiveAlbumNotice by remember { mutableStateOf(liveAlbumNotice) }
        if (liveAlbumNotice != null) lastLiveAlbumNotice = liveAlbumNotice
        var lastSkippedSong by remember { mutableStateOf(skippedSongNotice) }
        if (skippedSongNotice != null) lastSkippedSong = skippedSongNotice
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .then(drift.contentModifier)
                .padding(start = 24.dp + bottomStartInset, top = 24.dp, end = 24.dp, bottom = 24.dp)
        ) {
            AnimatedVisibility(
                visible = liveAlbumNotice != null && !isTvMode,
                enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { it },
                exit = fadeOut(tween(400)) + slideOutVertically(tween(400)) { it }
            ) {
                lastLiveAlbumNotice?.let { notice ->
                    LiveAlbumNoticeBar(notice, onRestore = onRestoreLiveAlbumSettings, onDismiss = onDismissLiveAlbumNotice)
                }
            }
            AnimatedVisibility(
                visible = skippedSongNotice != null && !isTvMode,
                enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { it },
                exit = fadeOut(tween(400)) + slideOutVertically(tween(400)) { it }
            ) {
                NowPlayingNoticeBar(
                    icon = Icons.Default.Explicit,
                    text = "Sonos skipped \"${lastSkippedSong.orEmpty()}\". If it's explicit, explicit songs are " +
                        "turned off in the Sonos app (Allow Explicit Content in its settings).",
                    onDismiss = onDismissSkippedSongNotice
                )
            }
        }

        if (showQueue) {
            QueueDialog(
                heading = trackState.album.ifBlank { "Up Next" },
                currentTitle = trackState.title,
                loadQueue = onLoadQueue,
                onPlayTrack = { index ->
                    onPlayQueueTrack(index)
                    showQueue = false
                },
                onDismiss = { showQueue = false }
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

// A notice along the bottom left of Now Playing, in the same glass as the
// buttons: an icon, the message, an optional action and a close button.
@Composable
private fun NowPlayingNoticeBar(
    icon: ImageVector,
    text: String,
    onDismiss: () -> Unit,
    actionLabel: String? = null,
    onAction: () -> Unit = {}
) {
    GlassSurface(shape = RoundedCornerShape(28.dp), modifier = Modifier.widthIn(max = 560.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false).padding(vertical = 8.dp))
            if (actionLabel != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

// "Live album: shuffle and crossfade turned off...", with Turn Back On; the
// close button leaves them off.
@Composable
private fun LiveAlbumNoticeBar(notice: LiveAlbumNotice, onRestore: () -> Unit, onDismiss: () -> Unit) {
    val turnedOff = listOfNotNull("shuffle".takeIf { notice.shuffleOff }, "crossfade".takeIf { notice.crossfadeOff })
        .joinToString(" and ")
    NowPlayingNoticeBar(
        icon = Icons.Default.Mic,
        text = "Live album: $turnedOff turned off so it plays as recorded",
        onDismiss = onDismiss,
        actionLabel = "Turn Back On",
        onAction = onRestore
    )
}

// TV mode's clock: the time, taking turns every TV_CLOCK_SWAP_MS with when
// what's on will finish (now + time remaining) while that's known - unless
// showEndsAt is off, when it's just the time.
@Composable
private fun TvClock(trackState: TrackState, use24HourClock: Boolean, showEndsAt: Boolean, modifier: Modifier = Modifier) {
    val currentTime = rememberMinuteClock()
    val knowsEnd = showEndsAt && trackState.durationSeconds > 0
    val remainingSeconds = if (knowsEnd) {
        (trackState.durationSeconds - rememberInterpolatedPosition(trackState)).coerceAtLeast(0)
    } else 0
    var showEnd by remember { mutableStateOf(false) }
    LaunchedEffect(knowsEnd) {
        showEnd = false
        while (knowsEnd) {
            delay(TV_CLOCK_SWAP_MS)
            showEnd = !showEnd
        }
    }
    // The finish time is set apart from the time now: gold, after a solid
    // gold "ENDS AT" badge.
    Crossfade(targetState = showEnd && knowsEnd, animationSpec = tween(600), label = "tvClock", modifier = modifier) { end ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (end) {
                Text(
                    text = "ENDS AT",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    color = Color.Black,
                    modifier = Modifier
                        .padding(end = 14.dp)
                        .background(TvClockEndColor, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
            val shown = if (end) {
                (Calendar.getInstance().clone() as Calendar).apply { add(Calendar.SECOND, remainingSeconds) }
            } else currentTime
            Text(
                text = formatClockTime(shown, use24HourClock),
                fontSize = 64.sp,
                fontWeight = FontWeight.Light,
                color = if (end) TvClockEndColor else Color.White.copy(alpha = 0.85f)
            )
        }
    }
}

// TV mode's artwork card when the show's logo is known: the logo over the
// HA artwork, blurred and darkened so the logo reads clearly. A TVmaze
// poster (no TMDB logo) fills the card, which has taken the poster's shape.
@Composable
private fun TvShowLogoArt(logo: TvShowLogo, artworkUrl: String, showName: String) {
    if (logo.kind == TvShowLogo.Kind.POSTER) {
        AsyncImage(
            model = logo.url,
            contentDescription = showName.ifBlank { "Show poster" },
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().background(Color.DarkGray)
        )
        return
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().background(Color.DarkGray)
    ) {
        if (artworkUrl.isNotBlank()) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(16.dp)
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        AsyncImage(
            model = logo.url,
            contentDescription = showName.ifBlank { "Show logo" },
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp)
        )
    }
}
