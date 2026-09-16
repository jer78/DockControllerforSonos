package com.example.sonosdockcontroller

import android.Manifest
import android.content.pm.PackageManager
import android.service.dreams.DreamService
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
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
                val trackState by manager.trackState.collectAsState()
                val haState by manager.haState.collectAsState()
                val isTvMode by manager.isTvMode.collectAsState()
                val currentVolume by manager.currentVolume.collectAsState()
                val albumHistory by manager.albumHistory.collectAsState()
                val speakerGroups by manager.speakerGroups.collectAsState()
                val showSpectrumAnalyzer by settingsManager.showSpectrumAnalyzer.collectAsState(initial = false)
                // A dream is the wrong place to prompt for a runtime permission - only
                // shown here if it was already granted from the main app's Settings.
                val micPermissionGranted = remember {
                    ContextCompat.checkSelfPermission(
                        this@SonosDreamService, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                }

                var isAmbientMode by remember { mutableStateOf(false) }
                var isTvAmbientMode by remember { mutableStateOf(false) }

                var lastIsPlaying by remember { mutableStateOf(trackState.isPlaying) }
                var lastIsTvMode by remember { mutableStateOf(isTvMode) }

                // Shared Transition Logic
                var transitioningAlbum by remember { mutableStateOf<AlbumHistoryItem?>(null) }
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

                LaunchedEffect(isTvMode, trackState.isPlaying) {
                    if (isTvMode && trackState.isPlaying) {
                        delay(20_000) // 20s of TV actively playing triggers the screensaver
                        isTvAmbientMode = true
                    } else {
                        // Not in TV mode, or TV mode but paused - go back to (or stay on)
                        // the now-playing screen instead of the screensaver.
                        isTvAmbientMode = false
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    val currentScreen = when {
                        isTvAmbientMode -> DockScreen.TV_AMBIENT
                        isAmbientMode -> DockScreen.COVER_FLOW
                        else -> DockScreen.NOW_PLAYING
                    }
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = dockScreenTransitionSpec,
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
                                        manager.playAlbum(album)
                                    },
                                    onRemoveAlbum = { manager.removeAlbumFromHistory(it) },
                                    onWakeUp = { isAmbientMode = false }
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
                                    onBrightnessChange = { manager.setLightBrightness(it) },
                                    onEnterAmbient = { isAmbientMode = true; transitioningAlbum = null },
                                    albumHistory = albumHistory,
                                    onOpenSettings = { finish() },
                                    speakerGroups = speakerGroups,
                                    onToggleJoin = { name, join -> manager.toggleSpeakerJoin(name, join) },
                                    onAlbumArtPositioned = { nowPlayingArtRect = it },
                                    onSetClimateTemp = { temp, high, low -> manager.setClimateTemperature(temp, high, low) },
                                    onSetClimateMode = { manager.setClimateHvacMode(it) },
                                    onSetClimateFan = { manager.setClimateFanMode(it) },
                                    onSetClimatePreset = { manager.setClimatePresetMode(it) },
                                    onSetLightOn = { manager.setLightOn(it) },
                                    onSetLightColorRgb = { r, g, b -> manager.setLightColorRgb(r, g, b) },
                                    onSetLightColorTemp = { manager.setLightColorTempKelvin(it) },
                                    showSpectrumAnalyzer = showSpectrumAnalyzer,
                                    micPermissionGranted = micPermissionGranted
                                )
                            }
                        }
                    }

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
                                    .clip(RoundedCornerShape(lerp(12f, 24f, animProgress.value).dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }
        }
        setContentView(composeView)
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
