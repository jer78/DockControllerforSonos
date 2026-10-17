package io.github.jer78.dockcontroller

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import android.os.Build
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// A text field suggesting matching entity ids as you type. The suggestions
// menu can't take focus: a focusable one (ExposedDropdownMenu's) pulled the
// keyboard off the field after each key, so deleting took a tap per letter.
@Composable
fun EntityAutocompleteField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<String>,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var fieldWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val filtered = remember(value, suggestions) {
        if (value.isBlank()) suggestions
        else suggestions.filter { it.contains(value, ignoreCase = true) }
    }

    Box(modifier = modifier) {
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
                .onSizeChanged { fieldWidth = it.width }
                .onFocusChanged { expanded = it.isFocused }
        )
        DropdownMenu(
            expanded = expanded && filtered.isNotEmpty(),
            onDismissRequest = { expanded = false },
            properties = PopupProperties(focusable = false),
            modifier = Modifier
                .width(with(density) { fieldWidth.toDp() })
                .heightIn(max = 280.dp)
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
        is HaConnectionStatus.Connected -> Color(0xFF81C784) to buildString {
            append("Connected to ${status.serverName}")
            if (status.version.isNotBlank()) append(" (v${status.version})")
        }
        is HaConnectionStatus.Failed -> Color(0xFFFF8A80) to status.reason
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

// One Settings > Home Assistant widget position: what kind of widget sits
// there (or None), which entity it controls, and its optional label - a
// compact card, two to a row.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HaWidgetSlotRow(
    position: Int,
    type: HaWidgetType?,
    entityId: String,
    suggestions: List<String>,
    showLabel: Boolean,
    label: String,
    onTypeChange: (HaWidgetType?) -> Unit,
    onEntityChange: (String) -> Unit,
    onShowLabelChange: (Boolean) -> Unit,
    onLabelChange: (String) -> Unit,
    // -1 = earlier, +1 = later.
    onMove: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .border(1.dp, Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("$position", fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))

            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.width(150.dp)
            ) {
                OutlinedTextField(
                    value = type?.label ?: "None",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Type") },
                    singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    (listOf(null) + HaWidgetType.entries).forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option?.label ?: "None") },
                            onClick = {
                                onTypeChange(option)
                                expanded = false
                            }
                        )
                    }
                }
            }

            if (type != null) {
                EntityAutocompleteField(
                    label = "Entity",
                    value = entityId,
                    onValueChange = onEntityChange,
                    suggestions = suggestions,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 28.dp)
        ) {
            if (type != null) {
                Switch(checked = showLabel, onCheckedChange = onShowLabelChange)
                OutlinedTextField(
                    value = label,
                    onValueChange = onLabelChange,
                    enabled = showLabel,
                    singleLine = true,
                    placeholder = { Text("Label: name from HA", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            // The first position can only move later, the last only earlier.
            IconButton(onClick = { onMove(-1) }, enabled = position > 1) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move position $position earlier")
            }
            IconButton(onClick = { onMove(1) }, enabled = position < MAX_HA_WIDGETS) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move position $position later")
            }
        }
    }
}

// Settings in the same dark, see-through glass as the other pop-ups
// (GlassDialog + GlassCard), laid out like an AlertDialog. Its controls use
// the dark colour scheme whatever the system's light/dark setting, so text
// fields, chips and switches read on the dark glass.
@Composable
private fun SettingsGlassDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    properties: DialogProperties = DialogProperties()
) {
    val context = LocalContext.current
    val darkScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(context) else darkColorScheme()
    MaterialTheme(colorScheme = darkScheme, typography = MaterialTheme.typography) {
        GlassDialog(onDismissRequest = onDismissRequest, properties = properties) {
            GlassCard(modifier = modifier) {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    Column(Modifier.padding(24.dp)) {
                        ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                            Box(Modifier.padding(bottom = 16.dp)) { title() }
                        }
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                            Box(Modifier.weight(1f, fill = false).padding(bottom = 24.dp)) { text() }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            dismissButton?.invoke()
                            confirmButton()
                        }
                    }
                }
            }
        }
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
    // Speaker and Cover Flow limit as currently picked in this dialog, even if
    // not saved yet - so first-time setup can refresh before Save & Apply.
    onRefreshFavorites: (speaker: String, albums: Boolean, playlists: Boolean, radio: Boolean) -> Unit,
    onSettingsSaved: () -> Unit,
    onDismiss: () -> Unit,
    micPermissionGranted: Boolean = false,
    onRequestMicPermission: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()

    var selectedSpeaker by remember { mutableStateOf("") }
    var haUrl by remember { mutableStateOf("") }
    var haToken by remember { mutableStateOf("") }
    // Home Assistant widget positions, as edited here: a type (null = empty)
    // and an entity id for each.
    val widgetTypes = remember { mutableStateListOf<HaWidgetType?>().apply { repeat(MAX_HA_WIDGETS) { add(null) } } }
    val widgetEntities = remember { mutableStateListOf<String>().apply { repeat(MAX_HA_WIDGETS) { add("") } } }
    val widgetShowLabels = remember { mutableStateListOf<Boolean>().apply { repeat(MAX_HA_WIDGETS) { add(false) } } }
    val widgetLabels = remember { mutableStateListOf<String>().apply { repeat(MAX_HA_WIDGETS) { add("") } } }
    var selectedHaMediaPlayer by remember { mutableStateOf("") }
    var tmdbApiKey by remember { mutableStateOf("") }
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
    var tvShowImages by remember { mutableStateOf(true) }
    var continueWithRandomAlbum by remember { mutableStateOf(false) }
    var nowPlayingHideNextUp by remember { mutableStateOf(false) }
    var tvFallbackImage by remember { mutableStateOf("") }
    var showSpectrumAnalyzer by remember { mutableStateOf(false) }
    var spectrumColorScheme by remember { mutableStateOf(SpectrumColorScheme.RAINBOW) }
    var spectrumHeightPercent by remember { mutableFloatStateOf(1f) }
    var spectrumConfineToAlbumArt by remember { mutableStateOf(false) }
    var pendingSpectrumHeight by remember { mutableStateOf<Job?>(null) }
    var musicBackgroundBlur by remember { mutableFloatStateOf(SettingsManager.BACKGROUND_BLUR_DEFAULT) }
    var tvBackgroundBlur by remember { mutableFloatStateOf(SettingsManager.BACKGROUND_BLUR_SAME_AS_MUSIC) }
    var pendingBackgroundBlur by remember { mutableStateOf<Job?>(null) }

    var fAlbums by remember { mutableStateOf(true) }
    var fPlaylists by remember { mutableStateOf(true) }
    var fRadio by remember { mutableStateOf(true) }

    val haManager = remember { HomeAssistantManager() }
    // Entity ids on the connected server, by widget type.
    var widgetEntityOptions by remember { mutableStateOf<Map<HaWidgetType, List<String>>>(emptyMap()) }
    var mediaPlayerEntities by remember { mutableStateOf<List<String>>(emptyList()) }

    var haConnection by remember { mutableStateOf<HaConnectionStatus>(HaConnectionStatus.NotTested) }
    // Once the token has connected, it's hidden behind a Change button.
    var tokenHidden by remember { mutableStateOf(false) }

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
                tokenHidden = true
                haManager.fetchEntities(url, token) { all ->
                    widgetEntityOptions = HaWidgetType.entries.associateWith { type ->
                        all.filter { it.startsWith("${type.domain}.") }.sorted()
                    }
                    mediaPlayerEntities = all.filter { it.startsWith("media_player.") }.sorted()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        selectedSpeaker = settingsManager.sonosSpeaker.first()
        haUrl = settingsManager.haUrl.first()
        haToken = settingsManager.haToken.first()
        settingsManager.haWidgets.first().forEachIndexed { i, slot ->
            widgetTypes[i] = slot?.type
            widgetEntities[i] = slot?.entityId ?: ""
            widgetShowLabels[i] = slot?.showLabel ?: false
            widgetLabels[i] = slot?.label ?: ""
        }
        selectedHaMediaPlayer = settingsManager.selectedHaMediaPlayer.first()
        tmdbApiKey = settingsManager.tmdbApiKey.first()
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
        tvShowImages = settingsManager.tvShowImages.first()
        continueWithRandomAlbum = settingsManager.continueWithRandomAlbum.first()
        tvFallbackImage = settingsManager.tvFallbackImage.first()
        fAlbums = settingsManager.filterAlbums.first()
        fPlaylists = settingsManager.filterPlaylists.first()
        fRadio = settingsManager.filterRadio.first()
        showSpectrumAnalyzer = settingsManager.showSpectrumAnalyzer.first()
        spectrumColorScheme = settingsManager.spectrumColorScheme.first()
        spectrumHeightPercent = settingsManager.spectrumHeightPercent.first()
        spectrumConfineToAlbumArt = settingsManager.spectrumConfineToAlbumArt.first()
        musicBackgroundBlur = settingsManager.musicBackgroundBlur.first()
        tvBackgroundBlur = settingsManager.tvBackgroundBlur.first()

        // Check the saved connection straight away, so the status shows
        // without needing to tap Connect - this also loads the entity lists
        // the fields below autocomplete from.
        if (haUrl.isNotBlank() && haToken.isNotBlank()) {
            connectToHa()
        }
    }

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

    SettingsGlassDialog(
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
                TabRow(selectedTabIndex = selectedTab, containerColor = Color.Transparent) {
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
                                    Text("Sonos Favorites:", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "Cover Flow shows your Sonos Favorites. Add or remove them in the Sonos app; " +
                                            "Sonos keeps up to about 70.",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
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
                                                onRefreshFavorites(selectedSpeaker, fAlbums, fPlaylists, fRadio)
                                            },
                                            enabled = !isRefreshing,
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.18f), contentColor = Color.White)
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
                                            color = if (refreshStatus.contains("Error")) Color(0xFFFF8A80) else Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
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

                                    // Saved a moment after the slider stops, like
                                    // the spectrum height.
                                    fun saveBlur(save: suspend () -> Unit) {
                                        pendingBackgroundBlur?.cancel()
                                        pendingBackgroundBlur = scope.launch {
                                            delay(300)
                                            save()
                                        }
                                    }
                                    fun blurLabel(dp: Float) = if (dp.roundToInt() == 0) "Off" else "${dp.roundToInt()}"
                                    Text("Background Blur:", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "How soft the artwork (or a TV show's background) behind Now Playing is.",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                    Text("Music: ${blurLabel(musicBackgroundBlur)}", fontSize = 12.sp, color = Color.Gray)
                                    Slider(
                                        value = musicBackgroundBlur,
                                        onValueChange = {
                                            musicBackgroundBlur = it
                                            saveBlur { settingsManager.setMusicBackgroundBlur(it) }
                                        },
                                        valueRange = 0f..SettingsManager.BACKGROUND_BLUR_MAX
                                    )
                                    val tvSameAsMusic = tvBackgroundBlur < 0f
                                    SettingsToggleRow("TV: Same as Music", tvSameAsMusic) { same ->
                                        tvBackgroundBlur = if (same) SettingsManager.BACKGROUND_BLUR_SAME_AS_MUSIC else musicBackgroundBlur
                                        scope.launch { settingsManager.setTvBackgroundBlur(tvBackgroundBlur) }
                                    }
                                    if (!tvSameAsMusic) {
                                        Text("TV: ${blurLabel(tvBackgroundBlur)}", fontSize = 12.sp, color = Color.Gray)
                                        Slider(
                                            value = tvBackgroundBlur,
                                            onValueChange = {
                                                tvBackgroundBlur = it
                                                saveBlur { settingsManager.setTvBackgroundBlur(it) }
                                            },
                                            valueRange = 0f..SettingsManager.BACKGROUND_BLUR_MAX
                                        )
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

                        // HOME ASSISTANT: connection and entities, in two
                        // columns to make the most of the dialog's width.
                        3 -> {
                            Text("Home Assistant Integration:", fontWeight = FontWeight.SemiBold)

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    value = haUrl,
                                    onValueChange = {
                                        haUrl = it
                                        haConnection = HaConnectionStatus.NotTested
                                    },
                                    label = { Text("Server URL") },
                                    placeholder = { Text("http://homeassistant.local:8123") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                if (tokenHidden) {
                                    OutlinedTextField(
                                        value = "Saved and hidden",
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Long-Lived Access Token") },
                                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                                        trailingIcon = {
                                            TextButton(onClick = { tokenHidden = false }) { Text("Change") }
                                        },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                } else {
                                    OutlinedTextField(
                                        value = haToken,
                                        onValueChange = {
                                            haToken = it
                                            haConnection = HaConnectionStatus.NotTested
                                        },
                                        label = { Text("Long-Lived Access Token") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

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

                            HorizontalDivider()
                            Text("Now Playing Widgets:", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Up to $MAX_HA_WIDGETS, shown left to right across the top of Now Playing in this order " +
                                    "(1 to 5 down the left, then 6 to 10). " +
                                    "You can also hold a widget on Now Playing for a second, then drag it to move it.",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                            val perColumn = (MAX_HA_WIDGETS + 1) / 2
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                for (column in 0 until 2) {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        for (i in column * perColumn until minOf((column + 1) * perColumn, MAX_HA_WIDGETS)) {
                                            HaWidgetSlotRow(
                                                position = i + 1,
                                                type = widgetTypes[i],
                                                entityId = widgetEntities[i],
                                                suggestions = widgetTypes[i]?.let { widgetEntityOptions[it] } ?: emptyList(),
                                                showLabel = widgetShowLabels[i],
                                                label = widgetLabels[i],
                                                onShowLabelChange = { widgetShowLabels[i] = it },
                                                onLabelChange = { widgetLabels[i] = it },
                                                onTypeChange = { type ->
                                                    // Keep the entity only if it still fits the new type.
                                                    if (type == null || !widgetEntities[i].startsWith("${type.domain}.")) widgetEntities[i] = ""
                                                    widgetTypes[i] = type
                                                },
                                                onEntityChange = { widgetEntities[i] = it },
                                                // Swaps with the neighboring position, so
                                                // empty positions move around too.
                                                onMove = { offset ->
                                                    val j = i + offset
                                                    widgetTypes[i] = widgetTypes[j].also { widgetTypes[j] = widgetTypes[i] }
                                                    widgetEntities[i] = widgetEntities[j].also { widgetEntities[j] = widgetEntities[i] }
                                                    widgetShowLabels[i] = widgetShowLabels[j].also { widgetShowLabels[j] = widgetShowLabels[i] }
                                                    widgetLabels[i] = widgetLabels[j].also { widgetLabels[j] = widgetLabels[i] }
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }
                            }
                            HorizontalDivider()

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(24.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // Left: TV mode.
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("TV Mode:", fontWeight = FontWeight.SemiBold)
                                    EntityAutocompleteField(
                                        label = "Fallback Media Player",
                                        value = selectedHaMediaPlayer,
                                        onValueChange = { selectedHaMediaPlayer = it },
                                        suggestions = mediaPlayerEntities,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    SettingsToggleRow("Replace TV Show Images", tvShowImages) {
                                        tvShowImages = it
                                        scope.launch { settingsManager.setTvShowImages(it) }
                                    }
                                    Text(
                                        if (tvShowImages) {
                                            "When a TV show is playing, its title logo from TMDB replaces the artwork " +
                                                "(with a free key from themoviedb.org), or else its poster from TVmaze."
                                        } else {
                                            "Off: TV mode shows the images Home Assistant sends."
                                        },
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                    if (tvShowImages) {
                                        OutlinedTextField(
                                            value = tmdbApiKey,
                                            onValueChange = { tmdbApiKey = it },
                                            label = { Text("TMDB API Key (optional)") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Text(
                                            "This product uses the TMDB API but is not endorsed or certified by TMDB.",
                                            fontSize = 12.sp,
                                            color = Color.Gray
                                        )
                                    }
                                }

                                // Right: experimental.
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Experimental:", fontWeight = FontWeight.SemiBold)
                                    SettingsToggleRow("Auto-Unmute After Commercials", autoUnmuteAfterAds) {
                                        autoUnmuteAfterAds = it
                                        scope.launch { settingsManager.setAutoUnmuteAfterAds(it) }
                                    }
                                    Text(
                                        "In TV mode, unmutes the speaker when the media player's time remaining jumps up - usually a sign a commercial break just ended.",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                }
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
                        List(MAX_HA_WIDGETS) { i ->
                            widgetTypes[i]?.let { HaWidgetSlot(it, widgetEntities[i], widgetShowLabels[i], widgetLabels[i]) }
                        },
                        selectedHaMediaPlayer,
                        tmdbApiKey,
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
}
