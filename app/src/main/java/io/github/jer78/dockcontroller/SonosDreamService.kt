package io.github.jer78.dockcontroller

import android.Manifest
import android.content.Intent
import android.view.KeyEvent
import android.content.pm.PackageManager
import android.service.dreams.DreamService
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.draw.blur
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class SonosDreamService : DreamService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val mViewModelStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = mViewModelStore
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private lateinit var manager: SonosDashboardManager
    private val settingsManager by lazy { SettingsManager(this) }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        manager = SonosDashboardManager.getInstance(this)
    }

    // Pop-ups are drawn inside the dream's own window (see InWindowDialogs.kt).
    private val dialogHost = InWindowDialogHost()

    // A dream's Back key normally ends the screensaver - close the top
    // pop-up first, if one is open.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && dialogHost.isShowingDialog) {
            if (event.action == KeyEvent.ACTION_UP) dialogHost.dismissTop()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        
        isInteractive = true
        isFullscreen = true
        
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        manager.startPolling()

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@SonosDreamService)
            setViewTreeViewModelStoreOwner(this@SonosDreamService)
            setViewTreeSavedStateRegistryOwner(this@SonosDreamService)
            
            setContent {
                CompositionLocalProvider(LocalInWindowDialogHost provides dialogHost) {
                    val trackState by manager.trackState.collectAsState()
                    val haState by manager.haState.collectAsState()
                    val isTvMode by manager.isTvMode.collectAsState()
                    val currentVolume by manager.currentVolume.collectAsState()
                    val audioSettings by manager.audioSettings.collectAsState()
                    val albumHistory by manager.albumHistory.collectAsState()
                    val speakerGroups by manager.speakerGroups.collectAsState()
                    val discoveredSpeakers by manager.discoveredSpeakers.collectAsState()
                    val refreshStatus by manager.favoritesRefreshStatus.collectAsState()
                    val refreshCount by manager.favoritesRefreshCount.collectAsState()
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
                    val settingsScope = rememberCoroutineScope()
                    val nowPlayingHidePrevious by settingsManager.nowPlayingHidePrevious.collectAsState(initial = false)
                    val nowPlayingHideNextUp by settingsManager.nowPlayingHideNextUp.collectAsState(initial = false)
                    val nowPlayingClockMode by settingsManager.nowPlayingClockMode.collectAsState(initial = NowPlayingClockMode.OFF)
                    val showEndsAt by settingsManager.showEndsAt.collectAsState(initial = true)
                    val tvScreensaverDelaySeconds by settingsManager.tvScreensaverDelaySeconds.collectAsState(
                        initial = SettingsManager.TV_SCREENSAVER_DELAY_DEFAULT_SECONDS
                    )
                    // A dream is the wrong place to prompt for a runtime permission - only
                    // shown here if it was already granted from the main app's Settings.
                    val micPermissionGranted = remember {
                        ContextCompat.checkSelfPermission(
                            this@SonosDreamService, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    }

                    var isAmbientMode by remember { mutableStateOf(false) }
                    var isTvAmbientMode by remember { mutableStateOf(false) }
                    var showSettings by remember { mutableStateOf(false) }

                    // Errors are logged (with timestamps) in the manager; CommandErrorFlash
                    // shows only fresh ones, so re-entering this screen doesn't re-flash an old error.
                    val recentErrors by manager.recentErrors.collectAsState()

                    // Dims the screen in a dark room instead of always running
                    // at full system brightness (see AmbientBrightnessController)
                    // - matters even more here than in MainActivity, since a
                    // screensaver runs for hours unattended overnight.
                    val ambientBrightnessController = remember { AmbientBrightnessController(this@SonosDreamService) }
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

                    // Shared Transition Logic
                    var transitioningAlbum by remember { mutableStateOf<AlbumHistoryItem?>(null) }
                    // How Now Playing was last left for Cover Flow (see dockScreenTransitionSpec).
                    var screenSlide by remember { mutableStateOf(ScreenSlide.VERTICAL) }
                    var transitionSourceRect by remember { mutableStateOf(Rect.Zero) }
                    var nowPlayingArtRect by remember { mutableStateOf<Rect?>(null) }

                    // Keep the flying album art overlay pinned in place until the new
                    // track is actually confirmed playing, instead of tearing it down
                    // on a fixed timer - see MainActivity for the matching rationale.
                    LaunchedEffect(transitioningAlbum) {
                        val album = transitioningAlbum ?: return@LaunchedEffect
                        delay(900) // let the fly-in animation land first
                        isAmbientMode = false

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

                    // Cover Flow / TV Ambient run most of the time here - back
                    // off polling while idle there instead of polling Sonos/HA
                    // every couple seconds around the clock.
                    LaunchedEffect(isAmbientMode, isTvAmbientMode) {
                        manager.setIdleScreen(isAmbientMode || isTvAmbientMode)
                    }

                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(if (dialogHost.isShowingDialog && inWindowBlurSupported) Modifier.blur(30.dp) else Modifier)
                    ) {
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
                                        onWakeUp = { isTvAmbientMode = false }
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
                                        onWakeUp = { isAmbientMode = false },
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
                                        isRefreshingFavorites = refreshStatus.startsWith("Starting"),
                                        // Clear of the Pixel Tablet's Home button and
                                        // status line, which hub mode draws over the
                                        // screensaver.
                                        bottomStartInset = 96.dp,
                                        topEndInset = 32.dp
                                    )
                                }
                                DockScreen.NOW_PLAYING -> {
                                    DockDashboardUI(
                                        trackState = trackState,
                                        haState = haState,
                                        isTvMode = isTvMode,
                                        currentVolume = currentVolume,
                                        onCommand = { action -> manager.sendSonosAction(action) },
                                        onVolumeChange = { vol -> manager.setSonosVolume(vol) },
                                        onSpeakerVolumeChange = { name, vol -> manager.setSpeakerVolume(name, vol) },
                                        onToggleMute = { manager.toggleMute() },
                                        onToggleShuffle = { manager.toggleShuffle() },
                                        onCycleRepeat = { manager.cycleRepeat() },
                                        coverFlowGridView = coverFlowGridView,
                                        onLoadQueue = { manager.loadQueue() },
                                        onPlayQueueTrack = { manager.playQueueTrack(it) },
                                        onSeek = { manager.seekTo(it) },
                                        onEnterAmbient = { slide -> screenSlide = slide; isAmbientMode = true; transitioningAlbum = null },
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
                                        onRequestMicPermission = { requestMicPermissionViaApp() },
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

                        // Shared Album Transition Overlay (see MainActivity for the
                        // matching implementation / rationale). Only while the
                        // dashboard is actually showing - otherwise re-entering
                        // Cover Flow before playback is confirmed leaves the stale
                        // art stuck on top of it.
                        if (!isAmbientMode && !isTvAmbientMode) transitioningAlbum?.let { album ->
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {
                                val animProgress = remember { Animatable(0f) }
                                LaunchedEffect(Unit) {
                                    animProgress.animateTo(1f, animationSpec = tween(900, easing = FastOutSlowInEasing))
                                }

                                val target = nowPlayingArtRect ?: transitionSourceRect
                                val density = LocalDensity.current
                                fun lerp(start: Float, stop: Float, fraction: Float) = start + (stop - start) * fraction
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

                    val savedFavorites by manager.savedFavorites.collectAsState()
                    val unplayableFavorite by manager.unplayableFavorite.collectAsState()

                    if (showSettings) {
                        SettingsModal(
                            discoveredSpeakers = discoveredSpeakers,
                            settingsManager = settingsManager,
                            refreshStatus = refreshStatus,
                            refreshCount = refreshCount,
                            onRefreshFavorites = { speaker, a, p, r -> manager.refreshFavorites(a, p, r, speaker) },
                            onSettingsSaved = { manager.refreshAfterSettingsSave() },
                            onDismiss = { showSettings = false },
                            micPermissionGranted = micPermissionGranted,
                            onRequestMicPermission = { requestMicPermissionViaApp() },
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

                    InWindowDialogLayer(dialogHost)
                }
            }
        }
        setContentView(composeView)

        // Immersive mode, same as MainActivity, so the system bars stay
        // hidden. It does NOT stop swipe-down opening the shade here: on
        // devices with SystemUI's dream overlay (Pixel Tablet) the overlay
        // intercepts any downward swipe over a dream, so Now Playing also
        // accepts a sideways swipe to reach Cover Flow.
        window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    // A dream can't show the runtime permission prompt, so hand off to the
    // app, which asks straight away (see MainActivity.EXTRA_REQUEST_MIC).
    // CLEAR_TOP + SINGLE_TOP so an already-running app gets the request via
    // onNewIntent instead of just being brought forward without it.
    private fun requestMicPermissionViaApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_REQUEST_MIC, true)
        )
        finish()
    }

    override fun onDetachedFromWindow() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        manager.stopPolling()
        super.onDetachedFromWindow()
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        mViewModelStore.clear()
        super.onDestroy()
    }
}
