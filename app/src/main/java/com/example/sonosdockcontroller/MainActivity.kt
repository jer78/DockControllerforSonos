package com.example.sonosdockcontroller

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val settingsManager by lazy { SettingsManager(this) }
    private lateinit var manager: SonosDashboardManager

    // Only requested when the Spectrum Analyzer setting is turned on (see SpectrumAnalyzer.kt).
    private var micPermissionGranted by mutableStateOf(false)
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> micPermissionGranted = granted }

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

        setContent {
            val trackState by manager.trackState.collectAsState()
            val haState by manager.haState.collectAsState()
            val isTvMode by manager.isTvMode.collectAsState()
            val currentVolume by manager.currentVolume.collectAsState()
            val discoveredSpeakers by manager.discoveredSpeakers.collectAsState()
            val albumHistory by manager.albumHistory.collectAsState()
            val speakerGroups by manager.speakerGroups.collectAsState()
            val showSpectrumAnalyzer by settingsManager.showSpectrumAnalyzer.collectAsState(initial = false)
            
            var showSettings by remember { mutableStateOf(false) }
            var isAmbientMode by remember { mutableStateOf(false) }
            var isTvAmbientMode by remember { mutableStateOf(false) }
            
            var lastIsPlaying by remember { mutableStateOf(trackState.isPlaying) }
            var lastIsTvMode by remember { mutableStateOf(isTvMode) }

            // Flash Error Logic
            var flashErrorMessage by remember { mutableStateOf<String?>(null) }
            val lastCommand = trackState.lastCommandSent
            LaunchedEffect(lastCommand) {
                if (lastCommand.contains("FAIL") || lastCommand.contains("ERR")) {
                    flashErrorMessage = lastCommand
                    delay(4000)
                    flashErrorMessage = null
                }
            }

            // Shared Transition Logic
            var transitioningAlbum by remember { mutableStateOf<AlbumHistoryItem?>(null) }
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

            LaunchedEffect(isAmbientMode, isTvAmbientMode) {
                if (isAmbientMode || isTvAmbientMode) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            LaunchedEffect(Unit) {
                val currentSpeaker = settingsManager.sonosSpeaker.first()
                if (currentSpeaker.isEmpty()) {
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
                                    manager.playAlbum(album)
                                },
                                onRemoveAlbum = { manager.removeAlbumFromHistory(it) },
                                onWakeUp = {
                                    isAmbientMode = false
                                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                }
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
                                onBrightnessChange = { manager.setLightBrightness(it) },
                                onEnterAmbient = {
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

                // Error Flash Box
                AnimatedVisibility(
                    visible = flashErrorMessage != null,
                    enter = slideInVertically { -it } + fadeIn(),
                    exit = slideOutVertically { -it } + fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp)
                ) {
                    Surface(
                        color = Color.Red,
                        shape = RoundedCornerShape(12.dp),
                        shadowElevation = 12.dp,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    ) {
                        Text(
                            text = flashErrorMessage ?: "",
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                }
                
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
                                .clip(RoundedCornerShape(lerp(12f, 24f, animProgress.value).dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }

            val refreshStatus by manager.favoritesRefreshStatus.collectAsState()

            if (showSettings) {
                SettingsModal(
                    discoveredSpeakers = discoveredSpeakers,
                    settingsManager = SettingsManager(this),
                    refreshStatus = refreshStatus,
                    albumHistoryCount = albumHistory.size,
                    onRefreshFavorites = { a, p, r -> manager.refreshFavorites(a, p, r) },
                    onClearHistory = { manager.clearAlbumHistory() },
                    onSettingsSaved = { manager.refreshAfterSettingsSave() },
                    onDismiss = { showSettings = false },
                    micPermissionGranted = micPermissionGranted,
                    onRequestMicPermission = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
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
