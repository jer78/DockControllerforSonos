package io.github.jer78.dockcontroller

import android.app.KeyguardManager
import android.content.Intent
import android.view.KeyEvent
import android.service.dreams.DreamService
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
                CompositionLocalProvider(
                    LocalInWindowDialogHost provides dialogHost,
                    LocalDeviceUnlockGate provides unlockGate
                ) {
                    val trackState by manager.trackState.collectAsState()
                    val haState by manager.haState.collectAsState()
                    val isTvMode by manager.isTvMode.collectAsState()
                    val currentVolume by manager.currentVolume.collectAsState()
                    val audioSettings by manager.audioSettings.collectAsState()
                    val liveAlbumNotice by manager.liveAlbumNotice.collectAsState()
                    val skippedSongNotice by manager.skippedSongNotice.collectAsState()
                    val albumHistory by manager.albumHistory.collectAsState()
                    val speakerGroups by manager.speakerGroups.collectAsState()
                    val refreshStatus by manager.favoritesRefreshStatus.collectAsState()
                    val musicBackgroundBlur by settingsManager.musicBackgroundBlur.collectAsState(initial = SettingsManager.BACKGROUND_BLUR_DEFAULT)
                    val tvBackgroundBlur by settingsManager.tvBackgroundBlur.collectAsState(initial = SettingsManager.BACKGROUND_BLUR_SAME_AS_MUSIC)
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
                    val coverFlowWidgetsMode by settingsManager.coverFlowWidgetsMode.collectAsState(initial = CoverFlowWidgetsMode.NEAR)
                    val gridRecentlyPlayed by settingsManager.gridRecentlyPlayed.collectAsState(initial = true)
                    val recentlyPlayed by manager.recentlyPlayed.collectAsState()
                    val tvScreensaverDelaySeconds by settingsManager.tvScreensaverDelaySeconds.collectAsState(
                        initial = SettingsManager.TV_SCREENSAVER_DELAY_DEFAULT_SECONDS
                    )
                    // No spectrum analyzer here: it would keep the microphone on
                    // (and Android's mic indicator lit) all the while the
                    // screensaver shows music - it's in the app only.

                    val swapWhilePlaying by settingsManager.swapWhilePlaying.collectAsState(initial = true)

                    var isAmbientMode by remember { mutableStateOf(false) }
                    var isTvAmbientMode by remember { mutableStateOf(false) }
                    // The dark-room blackout has the screen fully black - see MainActivity.
                    var screenBlack by remember { mutableStateOf(false) }
                    // On Cover Flow because of Show Cover Flow Between Songs,
                    // rather than idle - see MainActivity.
                    var swappedWhilePlaying by remember { mutableStateOf(false) }
                    LaunchedEffect(isAmbientMode, trackState.isPlaying) {
                        if (!isAmbientMode) swappedWhilePlaying = false
                        else if (!trackState.isPlaying) {
                            delay(10_000)
                            swappedWhilePlaying = false
                        }
                    }
                    // Artist suggestions - see MainActivity.
                    val lastFmApiKey by settingsManager.lastFmApiKey.collectAsState(initial = "")
                    val artistSuggestions by settingsManager.artistSuggestions.collectAsState(initial = true)
                    var suggestions by remember { mutableStateOf<List<SuggestedAlbum>?>(null) }
                    var returnAfterSuggestions by remember { mutableStateOf(false) }
                    LaunchedEffect(isAmbientMode) {
                        if (!isAmbientMode) {
                            suggestions = null
                            returnAfterSuggestions = false
                        }
                    }
                    fun closeSuggestions() {
                        suggestions = null
                        if (returnAfterSuggestions) isAmbientMode = false
                        returnAfterSuggestions = false
                    }
                    ReturnForNextSongEffect(trackState, swappedWhilePlaying) {
                        if (suggestions != null) returnAfterSuggestions = true else isAmbientMode = false
                    }

                    // Errors are logged (with timestamps) in the manager; CommandErrorFlash
                    // shows only fresh ones, so re-entering this screen doesn't re-flash an old error.
                    val recentErrors by manager.recentErrors.collectAsState()

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

                    // TV screensaver timing - see MainActivity.
                    LaunchedEffect(isTvMode, isTvAmbientMode, tvScreensaverDelaySeconds) {
                        if (!isTvMode) {
                            isTvAmbientMode = false
                        } else if (!isTvAmbientMode) {
                            delay(tvScreensaverDelaySeconds * 1000L) // user-configurable TV screensaver delay
                            isTvAmbientMode = true
                        }
                    }
                    LaunchedEffect(trackState.isPlaying) {
                        if (isTvMode && !trackState.isPlaying) isTvAmbientMode = false
                    }

                    // Cover Flow / TV Ambient run most of the time here - back
                    // off polling while idle there instead of polling Sonos/HA
                    // every couple seconds around the clock.
                    LaunchedEffect(isAmbientMode, isTvAmbientMode, swappedWhilePlaying) {
                        manager.setIdleScreen((isAmbientMode && !swappedWhilePlaying) || isTvAmbientMode)
                    }

                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(if (dialogHost.isShowingDialog && inWindowBlurSupported) Modifier.blur(30.dp) else Modifier)
                    ) {
                        val currentScreen = when {
                            isTvAmbientMode -> DockScreen.TV_AMBIENT
                            isAmbientMode && suggestions != null -> DockScreen.SUGGESTIONS
                            isAmbientMode -> DockScreen.COVER_FLOW
                            else -> DockScreen.NOW_PLAYING
                        }
                        SuggestionsEffect(
                            active = artistSuggestions && currentScreen == DockScreen.COVER_FLOW && trackState.isPlaying &&
                                !isTvMode && !dialogHost.isShowingDialog && transitioningAlbum == null,
                            artist = trackState.artist,
                            apiKey = lastFmApiKey,
                            favorites = albumHistory,
                            onShow = { suggestions = it }
                        )
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
                                DockScreen.SUGGESTIONS -> {
                                    // Kept while it animates away, after suggestions is cleared.
                                    val shown = remember { suggestions.orEmpty() }
                                    val sonosApp = remember { sonosAppLaunchIntent(this@SonosDreamService) }
                                    SuggestionsScreen(
                                        suggestions = shown,
                                        playingArtist = trackState.artist,
                                        onDone = { closeSuggestions() },
                                        onOpenSonosApp = sonosApp?.let { intent -> { openSonosApp(intent) } }
                                    )
                                }
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
                                        initialGridPosition = manager.coverFlowGridPosition,
                                        onGridPositionChanged = { manager.coverFlowGridPosition = it },
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
                                        topEndInset = 32.dp,
                                        haState = haState,
                                        widgetsMode = coverFlowWidgetsMode,
                                        onHaCommand = { manager.sendHaCommand(it) },
                                        onHaCommandWithResult = { command, onResult -> manager.sendHaCommand(command, onResult) },
                                        onReorderHaWidgets = { manager.reorderHaWidgets(it) },
                                        recentlyPlayed = recentlyPlayed,
                                        showRecentlyPlayed = gridRecentlyPlayed,
                                        startScrolling = swappedWhilePlaying,
                                        suggestions = if (artistSuggestions && lastFmApiKey.isNotBlank()) {
                                            ArtistSuggestions.latestFor(trackState.artist)
                                        } else emptyList(),
                                        canReplayTrack = { manager.canReplay(it) },
                                        onReplayTrack = { manager.replayTrack(it) },
                                        paused = screenBlack,
                                        loadAlbumTracks = { manager.albumTracks(it) },
                                        onPlayAlbumTrack = { album, track, rect ->
                                            transitionSourceRect = rect
                                            transitioningAlbum = album
                                            manager.playAlbumFromTrack(album, track)
                                        },
                                        onShowSuggestions = {
                                            suggestions = ArtistSuggestions.latestFor(trackState.artist).ifEmpty { null }
                                        }
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
                                        // Settings can't be scrolled inside a dream,
                                        // so it opens in the app instead.
                                        onOpenSettings = { openSettingsViaApp() },
                                        speakerGroups = speakerGroups,
                                        onToggleJoin = { name, join -> manager.toggleSpeakerJoin(name, join) },
                                        onAlbumArtPositioned = { nowPlayingArtRect = it },
                                        onHaCommand = { manager.sendHaCommand(it) },
                                        onReorderHaWidgets = { manager.reorderHaWidgets(it) },
                                        onHaCommandWithResult = { command, onResult -> manager.sendHaCommand(command, onResult) },
                                        musicBackgroundBlur = musicBackgroundBlur,
                                        tvBackgroundBlur = tvBackgroundBlur,
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
                                        showEndsAt = showEndsAt,
                                        liveAlbumNotice = liveAlbumNotice,
                                        onRestoreLiveAlbumSettings = { manager.restoreAfterLiveAlbum() },
                                        onDismissLiveAlbumNotice = { manager.dismissLiveAlbumNotice() },
                                        skippedSongNotice = skippedSongNotice,
                                        onDismissSkippedSongNotice = { manager.dismissSkippedSongNotice() },
                                        canReplayTrack = { manager.canReplay(it) },
                                        onReplayTrack = { manager.replayTrack(it) },
                                        onRefreshSpeakerSettings = {
                                            manager.refreshSpeakerGroups()
                                            manager.refreshAudioSettings()
                                        },
                                        // The Pixel Tablet's Home button, as for Cover Flow.
                                        bottomStartInset = 96.dp,
                                        swapWhilePlaying = swapWhilePlaying && !dialogHost.isShowingDialog,
                                        onSwapWhilePlaying = {
                                            screenSlide = ScreenSlide.VERTICAL
                                            swappedWhilePlaying = true
                                            isAmbientMode = true
                                        }
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

                    val unplayableFavorite by manager.unplayableFavorite.collectAsState()

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

                    // Dims the screen in a dark room, and blacks out Cover
                    // Flow / TV Ambient there (see DarkRoomBlackout) - over
                    // any pop-up too, since a screensaver runs unattended
                    // all night.
                    DarkRoomBlackout(idle = isAmbientMode || isTvAmbientMode, window = window, onFullyBlack = { screenBlack = it })
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

    // A dream's window doesn't scroll reliably, so Settings opens in the app
    // instead. CLEAR_TOP + SINGLE_TOP so an already-running app gets the
    // request via onNewIntent instead of just being brought forward without it.
    private fun openSettingsViaApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
        )
        finish()
    }

    // Unlocking a lock or disarming an alarm from here needs the tablet
    // unlocked first: opening the app makes Android ask for it, and the
    // action can then be done there.
    private val unlockGate by lazy {
        val keyguard = getSystemService(KeyguardManager::class.java)
        DeviceUnlockGate(
            isLocked = { keyguard?.isKeyguardLocked == true },
            requestUnlock = {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                finish()
            }
        )
    }

    // From artist suggestions - the dream steps aside for it, like Settings.
    private fun openSonosApp(intent: Intent) {
        startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
