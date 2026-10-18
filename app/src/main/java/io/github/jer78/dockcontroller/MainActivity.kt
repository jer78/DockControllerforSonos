package io.github.jer78.dockcontroller

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val settingsManager by lazy { SettingsManager(this) }
    private lateinit var manager: SonosDashboardManager

    // Only requested when the Spectrum Analyzer setting is turned on (see SpectrumAnalyzer.kt).
    private var micPermissionGranted by mutableStateOf(false)
    // Set from EXTRA_OPEN_SETTINGS; the composition opens Settings and clears it.
    private var openSettingsRequested by mutableStateOf(false)
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> micPermissionGranted = granted }

    // Asks for the mic, or - once Android has stopped showing the prompt
    // (denied before, or "Don't allow" twice) - opens this app's page in
    // system Settings so it can be switched on there. onResume picks up the
    // change on the way back.
    private fun requestMicPermission() {
        if (micPermissionGranted) return
        val prefs = getSharedPreferences("permissions", MODE_PRIVATE)
        val askedBefore = prefs.getBoolean("mic_asked", false)
        if (askedBefore && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            )
        } else {
            prefs.edit().putBoolean("mic_asked", true).apply()
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun handleMicRequestIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_REQUEST_MIC, false) == true) {
            intent.removeExtra(EXTRA_REQUEST_MIC)
            requestMicPermission()
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true) {
            intent.removeExtra(EXTRA_OPEN_SETTINGS)
            openSettingsRequested = true
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleMicRequestIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        micPermissionGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        // Set by SonosDreamService, which can't show a permission prompt
        // itself, to have the app ask for the mic as soon as it opens.
        const val EXTRA_REQUEST_MIC = "request_mic"
        // Set by SonosDreamService, whose Settings can't be scrolled inside
        // the screensaver, to have the app open Settings as soon as it opens.
        const val EXTRA_OPEN_SETTINGS = "open_settings"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        micPermissionGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        manager = SonosDashboardManager.getInstance(this)
        
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        
        manager.startPolling()
        handleMicRequestIntent(intent)

        setContent {
            val trackState by manager.trackState.collectAsState()
            val haState by manager.haState.collectAsState()
            val isTvMode by manager.isTvMode.collectAsState()
            val currentVolume by manager.currentVolume.collectAsState()
            val audioSettings by manager.audioSettings.collectAsState()
            val discoveredSpeakers by manager.discoveredSpeakers.collectAsState()
            val albumHistory by manager.albumHistory.collectAsState()
            val speakerGroups by manager.speakerGroups.collectAsState()
            val showSpectrumAnalyzer by settingsManager.showSpectrumAnalyzer.collectAsState(initial = false)
            val spectrumColorScheme by settingsManager.spectrumColorScheme.collectAsState(initial = SpectrumColorScheme.RAINBOW)
            val spectrumHeightPercent by settingsManager.spectrumHeightPercent.collectAsState(initial = 1f)
            val musicBackgroundBlur by settingsManager.musicBackgroundBlur.collectAsState(initial = SettingsManager.BACKGROUND_BLUR_DEFAULT)
            val tvBackgroundBlur by settingsManager.tvBackgroundBlur.collectAsState(initial = SettingsManager.BACKGROUND_BLUR_SAME_AS_MUSIC)
            val spectrumConfineToAlbumArt by settingsManager.spectrumConfineToAlbumArt.collectAsState(initial = false)
            val use24HourClock by settingsManager.use24HourClock.collectAsState(initial = true)
            val coverFlowReflection by settingsManager.coverFlowReflection.collectAsState(initial = SettingsManager.COVER_FLOW_REFLECTION_DEFAULT)
            val coverFlowGlassTable by settingsManager.coverFlowGlassTable.collectAsState(initial = SettingsManager.COVER_FLOW_GLASS_TABLE_DEFAULT)
            val coverFlowSideLight by settingsManager.coverFlowSideLight.collectAsState(initial = SettingsManager.COVER_FLOW_SIDE_LIGHT_DEFAULT)
            val coverFlowHidePrevious by settingsManager.coverFlowHidePrevious.collectAsState(initial = false)
            val coverFlowHideNextUp by settingsManager.coverFlowHideNextUp.collectAsState(initial = false)
            val coverFlowHideNowPlaying by settingsManager.coverFlowHideNowPlaying.collectAsState(initial = false)
            val coverFlowGridView by settingsManager.coverFlowGridView.collectAsState(initial = false)
            val coverFlowSortByArtist by settingsManager.coverFlowSortByArtist.collectAsState(initial = true)
            val favoritesRefreshStatus by manager.favoritesRefreshStatus.collectAsState()
            val settingsScope = rememberCoroutineScope()
            val nowPlayingHidePrevious by settingsManager.nowPlayingHidePrevious.collectAsState(initial = false)
            val nowPlayingHideNextUp by settingsManager.nowPlayingHideNextUp.collectAsState(initial = false)
            val nowPlayingClockMode by settingsManager.nowPlayingClockMode.collectAsState(initial = NowPlayingClockMode.OFF)
            val showEndsAt by settingsManager.showEndsAt.collectAsState(initial = true)
            val tvScreensaverDelaySeconds by settingsManager.tvScreensaverDelaySeconds.collectAsState(
                initial = SettingsManager.TV_SCREENSAVER_DELAY_DEFAULT_SECONDS
            )

            var showSettings by remember { mutableStateOf(false) }
            var isAmbientMode by remember { mutableStateOf(false) }
            var isTvAmbientMode by remember { mutableStateOf(false) }

            // Dims the screen in a dark room instead of always running at
            // full system brightness (see AmbientBrightnessController).
            val ambientBrightnessController = remember { AmbientBrightnessController(this@MainActivity) }
            DisposableEffect(Unit) {
                ambientBrightnessController.start()
                onDispose { ambientBrightnessController.stop() }
            }
            val ambientBrightness by ambientBrightnessController.brightness.collectAsState()
            LaunchedEffect(ambientBrightness) {
                val attributes = window.attributes
                attributes.screenBrightness = ambientBrightness
                window.attributes = attributes
            }
            
            var lastIsPlaying by remember { mutableStateOf(trackState.isPlaying) }
            var lastIsTvMode by remember { mutableStateOf(isTvMode) }

            // Errors are logged (with timestamps) in the manager; CommandErrorFlash
            // shows only fresh ones, so re-entering this screen doesn't re-flash an old error.
            val recentErrors by manager.recentErrors.collectAsState()

            // Shared Transition Logic
            var transitioningAlbum by remember { mutableStateOf<AlbumHistoryItem?>(null) }
            // How Now Playing was last left for Cover Flow (see dockScreenTransitionSpec).
            var screenSlide by remember { mutableStateOf(ScreenSlide.VERTICAL) }
            var transitionSourceRect by remember { mutableStateOf(Rect.Zero) }
            var nowPlayingArtRect by remember { mutableStateOf<Rect?>(null) }

            // Keep the flying album art overlay pinned in place until the new
            // track is actually confirmed playing (matching art + isPlaying),
            // instead of tearing it down on a fixed timer. Otherwise the
            // dashboard underneath gets revealed while playAlbum() is still
            // resolving, showing the old/blank art for a beat before the next
            // poll catches up - the flash this is meant to avoid.
            LaunchedEffect(transitioningAlbum) {
                val album = transitioningAlbum ?: return@LaunchedEffect
                delay(900) // let the fly-in animation land first
                isAmbientMode = false
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                val deadline = System.currentTimeMillis() + 8000
                while (System.currentTimeMillis() < deadline) {
                    val current = manager.trackState.value
                    if (current.isPlaying && current.albumArtUrl == album.artUrl) break
                    delay(200)
                }
                transitioningAlbum = null
            }

            // Wake-on-Play logic
            LaunchedEffect(trackState.isPlaying, isTvMode) {
                val musicStarted = trackState.isPlaying && !lastIsPlaying
                val tvStarted = isTvMode && !lastIsTvMode

                if (musicStarted || tvStarted) {
                    isAmbientMode = false
                    isTvAmbientMode = false
                }

                lastIsPlaying = trackState.isPlaying
                lastIsTvMode = isTvMode
            }

            // Idle timer: whenever the Now Playing screen is showing while
            // paused (whether it just paused, or the user tapped from Cover
            // Flow into a still-paused Now Playing), drop back to Cover Flow
            // after a short idle period. Keyed on isAmbientMode too so
            // manually waking up restarts the countdown.
            LaunchedEffect(isAmbientMode, trackState.isPlaying, isTvMode) {
                if (!isAmbientMode && !trackState.isPlaying && !isTvMode) {
                    delay(10_000)
                    isAmbientMode = true
                }
            }

            LaunchedEffect(isTvMode, trackState.isPlaying, tvScreensaverDelaySeconds) {
                if (isTvMode && trackState.isPlaying) {
                    delay(tvScreensaverDelaySeconds * 1000L) // user-configurable TV screensaver delay
                    isTvAmbientMode = true
                } else {
                    // Not in TV mode, or TV mode but paused - go back to (or stay on)
                    // the now-playing screen instead of the screensaver.
                    isTvAmbientMode = false
                }
            }

            LaunchedEffect(isAmbientMode, isTvAmbientMode) {
                if (isAmbientMode || isTvAmbientMode) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                // Cover Flow / TV Ambient run most of the time on a
                // screensaver - back off polling while idle there instead of
                // polling Sonos/HA every couple seconds around the clock.
                manager.setIdleScreen(isAmbientMode || isTvAmbientMode)
            }

            LaunchedEffect(Unit) {
                val currentSpeaker = settingsManager.sonosSpeaker.first()
                if (currentSpeaker.isEmpty()) {
                    manager.triggerDiscovery()
                    showSettings = true
                }
            }

            LaunchedEffect(openSettingsRequested) {
                if (openSettingsRequested) {
                    openSettingsRequested = false
                    manager.triggerDiscovery()
                    showSettings = true
                }
            }

            Box(Modifier.fillMaxSize()) {
                val currentScreen = when {
                    isTvAmbientMode -> DockScreen.TV_AMBIENT
                    isAmbientMode -> DockScreen.COVER_FLOW
                    else -> DockScreen.NOW_PLAYING
                }
                // The swipe direction only shapes the trip to Cover Flow and back -
                // once Now Playing is reached again, later switches are vertical.
                LaunchedEffect(currentScreen) {
                    if (currentScreen == DockScreen.NOW_PLAYING) screenSlide = ScreenSlide.VERTICAL
                }
                AnimatedContent(
                    targetState = currentScreen,
                    transitionSpec = dockScreenTransitionSpec(screenSlide),
                    modifier = Modifier.fillMaxSize(),
                    label = "screenTransition"
                ) { screen ->
                    when (screen) {
                        DockScreen.TV_AMBIENT -> {
                            TvAmbientScreen(
                                trackState = trackState,
                                onWakeUp = {
                                    isTvAmbientMode = false
                                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                }
                            )
                        }
                        DockScreen.COVER_FLOW -> {
                            AmbientClockScreen(
                                albumHistory = albumHistory,
                                trackState = trackState,
                                onPlayAlbum = { album, rect ->
                                    transitionSourceRect = rect
                                    transitioningAlbum = album
                                    manager.playAlbum(album, askIfUnplayable = true)
                                },
                                onWakeUp = {
                                    isAmbientMode = false
                                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                },
                                use24HourClock = use24HourClock,
                                reflectionStrength = coverFlowReflection,
                                glassTableStrength = coverFlowGlassTable,
                                sideLightStrength = coverFlowSideLight,
                                onDeleteFavorite = { manager.deleteFavoriteEverywhere(it) },
                                initialCenterAlbum = manager.coverFlowCenterAlbum,
                                onCenterAlbumChanged = { manager.coverFlowCenterAlbum = it },
                                hidePrevious = coverFlowHidePrevious,
                                hideNextUp = coverFlowHideNextUp,
                                hideNowPlaying = coverFlowHideNowPlaying,
                                weather = haState.firstWeather(),
                                gridView = coverFlowGridView,
                                onGridViewChange = { grid -> settingsScope.launch { settingsManager.setCoverFlowGridView(grid) } },
                                sortByArtist = coverFlowSortByArtist,
                                onSortChange = { byArtist -> settingsScope.launch { settingsManager.setCoverFlowSortByArtist(byArtist) } },
                                onRefreshFavorites = { onResult -> manager.refreshSavedFavorites(onResult) },
                                isRefreshingFavorites = favoritesRefreshStatus.startsWith("Starting")
                            )
                        }
                        DockScreen.NOW_PLAYING -> {
                            DockDashboardUI(
                                trackState = trackState,
                                haState = haState,
                                isTvMode = isTvMode,
                                currentVolume = currentVolume,
                                onCommand = { manager.sendSonosAction(it) },
                                onVolumeChange = { manager.setSonosVolume(it) },
                                onSpeakerVolumeChange = { name, vol -> manager.setSpeakerVolume(name, vol) },
                                onToggleMute = { manager.toggleMute() },
                                onToggleShuffle = { manager.toggleShuffle() },
                                onCycleRepeat = { manager.cycleRepeat() },
                                coverFlowGridView = coverFlowGridView,
                                onLoadQueue = { manager.loadQueue() },
                                onPlayQueueTrack = { manager.playQueueTrack(it) },
                                onSeek = { manager.seekTo(it) },
                                onEnterAmbient = { slide ->
                                    screenSlide = slide
                                    isAmbientMode = true
                                    transitioningAlbum = null
                                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                },
                                albumHistory = albumHistory,
                                onOpenSettings = {
                                    manager.triggerDiscovery()
                                    showSettings = true
                                },
                                speakerGroups = speakerGroups,
                                onToggleJoin = { name, join -> manager.toggleSpeakerJoin(name, join) },
                                onAlbumArtPositioned = { nowPlayingArtRect = it },
                                onHaCommand = { manager.sendHaCommand(it) },
                                onReorderHaWidgets = { manager.reorderHaWidgets(it) },
                                onHaCommandWithResult = { command, onResult -> manager.sendHaCommand(command, onResult) },
                                showSpectrumAnalyzer = showSpectrumAnalyzer,
                                micPermissionGranted = micPermissionGranted,
                                onRequestMicPermission = { requestMicPermission() },
                                spectrumColorScheme = spectrumColorScheme,
                                spectrumHeightPercent = spectrumHeightPercent,
                                musicBackgroundBlur = musicBackgroundBlur,
                                tvBackgroundBlur = tvBackgroundBlur,
                                spectrumConfineToAlbumArt = spectrumConfineToAlbumArt,
                                audioSettings = audioSettings,
                                onToggleNightMode = { manager.toggleNightMode() },
                                onToggleDialogMode = { manager.toggleDialogMode() },
                                onSetBass = { manager.setBass(it) },
                                onSetTreble = { manager.setTreble(it) },
                                onSetLoudness = { manager.setLoudness(it) },
                                onToggleSubEnabled = { manager.toggleSubEnabled() },
                                hidePrevious = nowPlayingHidePrevious,
                                hideNextUp = nowPlayingHideNextUp,
                                clockMode = nowPlayingClockMode,
                                use24HourClock = use24HourClock,
                                showEndsAt = showEndsAt
                            )
                        }
                    }
                }

                // Error Flash Box
                CommandErrorFlash(recentErrors.firstOrNull(), Modifier.align(Alignment.TopCenter))
                
                // Shared Album Transition Overlay: animates the tapped cover-flow
                // card's real on-screen rect into the now-playing art's real rect,
                // so it visually flies from the carousel into the dashboard. Only
                // while the dashboard is actually showing - if the user re-enters
                // Cover Flow before playback is confirmed (still waiting on
                // transitioningAlbum's own LaunchedEffect below), this stops the
                // stale art from staying stuck on top of it.
                if (!isAmbientMode && !isTvAmbientMode) transitioningAlbum?.let { album ->
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {
                        val animProgress = remember { Animatable(0f) }
                        LaunchedEffect(Unit) {
                            animProgress.animateTo(1f, animationSpec = tween(900, easing = FastOutSlowInEasing))
                        }

                        val target = nowPlayingArtRect ?: transitionSourceRect
                        val density = LocalDensity.current
                        val left = lerp(transitionSourceRect.left, target.left, animProgress.value)
                        val top = lerp(transitionSourceRect.top, target.top, animProgress.value)
                        val width = lerp(transitionSourceRect.width, target.width, animProgress.value)
                        val height = lerp(transitionSourceRect.height, target.height, animProgress.value)

                        AsyncImage(
                            model = album.artUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                                .size(with(density) { width.toDp() }, with(density) { height.toDp() })
                                .clip(RoundedCornerShape(lerp(0f, 24f, animProgress.value).dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }

            val refreshStatus by manager.favoritesRefreshStatus.collectAsState()
            val refreshCount by manager.favoritesRefreshCount.collectAsState()

            val savedFavorites by manager.savedFavorites.collectAsState()
            val unplayableFavorite by manager.unplayableFavorite.collectAsState()

            if (showSettings) {
                SettingsModal(
                    discoveredSpeakers = discoveredSpeakers,
                    settingsManager = SettingsManager(this),
                    refreshStatus = refreshStatus,
                    refreshCount = refreshCount,
                    onRefreshFavorites = { speaker, a, p, r -> manager.refreshFavorites(a, p, r, speaker) },
                    onSettingsSaved = { manager.refreshAfterSettingsSave() },
                    onDismiss = { showSettings = false },
                    micPermissionGranted = micPermissionGranted,
                    onRequestMicPermission = { requestMicPermission() },
                    savedFavorites = savedFavorites,
                    onRemoveFromSonos = { manager.deleteSonosFavorite(it, keepInApp = true) },
                    onDeleteEverywhere = { manager.deleteSonosFavorite(it, keepInApp = false) },
                    onDeleteSaved = { manager.deleteSavedFavorite(it) }
                )
            }

            unplayableFavorite?.let { item ->
                UnplayableFavoriteDialog(
                    item = item,
                    onKeep = { manager.dismissUnplayableFavorite() },
                    onDelete = {
                        manager.dismissUnplayableFavorite()
                        manager.deleteSavedFavorite(item)
                    }
                )
            }
        }
    }

    private fun lerp(start: Float, stop: Float, fraction: Float): Float =
        start + (stop - start) * fraction

    override fun onDestroy() {
        manager.stopPolling()
        super.onDestroy()
    }
}
