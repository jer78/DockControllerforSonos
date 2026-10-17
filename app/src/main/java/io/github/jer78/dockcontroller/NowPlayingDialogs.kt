package io.github.jer78.dockcontroller

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

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

    // Lighter than Color.Blue so "Joined" reads on the dark glass.
    val joinedBlue = Color(0xFF64B5F6)

    GlassDialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.width(420.dp)) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Speaker Groups", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

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
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
                                ) { Text("Join All") }
                            }
                            if (anyJoined) {
                                OutlinedButton(
                                    onClick = {
                                        speakerGroups.filter { displayedJoin(it) }
                                            .forEach { setJoin(it.name, false) }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
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
                                .background(if (isJoined) joinedBlue.copy(alpha = 0.15f) else Color.Transparent)
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(id = sonosModelIconRes(group.model)),
                                    contentDescription = null,
                                    tint = if (isJoined) joinedBlue else Color.Gray,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(group.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                                    Text(if (isJoined) "Joined" else "Available", fontSize = 12.sp, color = if (isJoined) joinedBlue else Color.Gray)
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
        }
    }
}

// Maps a Sonos model_name (from SoCo's get_speaker_info) to a per-model
// icon - falls back to the generic Sonos wordmark for anything we don't
// have a dedicated icon for (Arc/Move's traced icons were impractically
// huge for a 24dp glyph, Play:1 etc. have none in the pack at all).
internal fun sonosModelIconRes(model: String): Int {
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
    climate: HaClimateState,
    onSetTemp: (Double?, Double?, Double?) -> Unit,
    onSetMode: (String) -> Unit,
    onSetFan: (String) -> Unit,
    onSetPreset: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val isRange = climate.targetTempHigh != null && climate.targetTempLow != null
    // HA reports whatever step the integration declares (often 0.1 or 1.0),
    // but a 0.5° resolution is the natural granularity for Celsius
    // thermostats - force it regardless of what the entity advertises.
    val effectiveStep = if (climate.temperatureUnit.contains("C", ignoreCase = true)) 0.5 else climate.tempStep
    var localTemp by remember(climate.targetTemp) { mutableStateOf(roundToStep(climate.targetTemp ?: climate.minTemp, effectiveStep)) }
    var localHigh by remember(climate.targetTempHigh) { mutableStateOf(roundToStep(climate.targetTempHigh ?: climate.maxTemp, effectiveStep)) }
    var localLow by remember(climate.targetTempLow) { mutableStateOf(roundToStep(climate.targetTempLow ?: climate.minTemp, effectiveStep)) }

    val modeColor = hvacModeColor(climate.hvacState)
    val scope = rememberCoroutineScope()
    var pendingSend by remember { mutableStateOf<Job?>(null) }

    fun scheduleSend() {
        pendingSend?.cancel()
        pendingSend = scope.launch {
            delay(600)
            if (isRange) onSetTemp(null, localHigh, localLow) else onSetTemp(localTemp, null, null)
        }
    }

    GlassDialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.width(420.dp)) {
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
                    Text(climate.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
                        val range = (climate.maxTemp - climate.minTemp).coerceAtLeast(0.1)

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
                            val lowFrac = ((localLow - climate.minTemp) / range).coerceIn(0.0, 1.0)
                            val highFrac = ((localHigh - climate.minTemp) / range).coerceIn(0.0, 1.0)
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
                            val frac = ((localTemp - climate.minTemp) / range).coerceIn(0.0, 1.0)
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
                            "Current: ${climate.climateTemp}${climate.temperatureUnit}" +
                                (climate.currentHumidity?.let { " · $it% humidity" } ?: ""),
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                        if (climate.hvacAction.isNotBlank()) {
                            Text(
                                climate.hvacAction.uppercase(),
                                color = modeColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            if (isRange) localLow = roundToStep((localLow - effectiveStep).coerceAtLeast(climate.minTemp), effectiveStep)
                            else localTemp = roundToStep((localTemp - effectiveStep).coerceAtLeast(climate.minTemp), effectiveStep)
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
                            if (isRange) localHigh = roundToStep((localHigh + effectiveStep).coerceAtMost(climate.maxTemp), effectiveStep)
                            else localTemp = roundToStep((localTemp + effectiveStep).coerceAtMost(climate.maxTemp), effectiveStep)
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

                if (climate.hvacModes.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        climate.hvacModes.forEach { mode ->
                            val selected = climate.hvacState == mode
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

                if (climate.fanModes.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Text("Fan", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Start))
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        climate.fanModes.forEach { fan ->
                            FilterChip(
                                selected = climate.fanMode == fan,
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

                if (climate.presetModes.isNotEmpty()) {
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
                                    climate.presetMode.ifBlank { "Select preset" }
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
                            climate.presetModes.forEach { preset ->
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
    light: HaLightState,
    onSetOn: (Boolean) -> Unit,
    onSetBrightness: (Int) -> Unit,
    onSetColorRgb: (Int, Int, Int) -> Unit,
    onSetColorTemp: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var localBrightness by remember(light.brightness) { mutableStateOf(light.brightness.toFloat()) }
    val scope = rememberCoroutineScope()
    var pendingBrightness by remember { mutableStateOf<Job?>(null) }

    fun scheduleBrightness(value: Int) {
        pendingBrightness?.cancel()
        pendingBrightness = scope.launch {
            delay(300)
            onSetBrightness(value)
        }
    }

    val supportsRgb = light.colorModes.any { it in listOf("rgb", "rgbw", "rgbww", "hs", "xy") }
    val supportsColorTemp = !supportsRgb && light.colorModes.any { it == "color_temp" }

    var localKelvin by remember(light.colorTempKelvin) {
        mutableStateOf((light.colorTempKelvin ?: ((light.minColorTempKelvin + light.maxColorTempKelvin) / 2)).toFloat())
    }
    var pendingTemp by remember { mutableStateOf<Job?>(null) }

    fun scheduleTemp(value: Int) {
        pendingTemp?.cancel()
        pendingTemp = scope.launch {
            delay(300)
            onSetColorTemp(value)
        }
    }

    GlassDialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.width(380.dp)) {
            Box {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(light.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }

                    // Same faint white sections as the Thermostat and Sound
                    // Settings pop-ups.
                    Surface(
                        color = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(if (light.isOn) "On" else "Off", color = Color.White, fontSize = 16.sp)
                            Switch(
                                checked = light.isOn,
                                onCheckedChange = onSetOn,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Yellow,
                                    checkedTrackColor = Color.Yellow.copy(alpha = 0.5f)
                                )
                            )
                        }
                    }

                    Surface(
                        color = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Brightness", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(10.dp))
                            LargeVolumeSlider(
                                value = localBrightness / 255f * 100f,
                                onValueChange = {
                                    val brightness = (it / 100f * 255f).roundToInt()
                                    localBrightness = brightness.toFloat()
                                    scheduleBrightness(brightness)
                                },
                                fillColor = Color(0xFFFFD54F),
                                height = 64.dp,
                                fontSize = 20.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .alpha(if (light.isOn) 1f else 0.4f)
                            )
                        }
                    }

                    if (supportsRgb) {
                        Surface(
                            color = Color.White.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
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
                                        val isSelected = light.rgbColor?.let {
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
                            }
                        }
                    } else if (supportsColorTemp) {
                        Surface(
                            color = Color.White.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Color Temperature", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(10.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Warm", color = Color(0xFFFFB74D), fontSize = 11.sp)
                                    Slider(
                                        value = localKelvin,
                                        onValueChange = {
                                            localKelvin = it
                                            scheduleTemp(it.toInt())
                                        },
                                        valueRange = light.minColorTempKelvin.toFloat()..light.maxColorTempKelvin.toFloat(),
                                        enabled = light.isOn,
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
        }
    }
}

@Composable
fun SoundSettingsDialog(
    audioSettings: SonosAudioSettings,
    onToggleNightMode: () -> Unit,
    onToggleDialogMode: () -> Unit,
    onSetBass: (Int) -> Unit,
    onSetTreble: (Int) -> Unit,
    onSetLoudness: (Boolean) -> Unit,
    onToggleSubEnabled: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var localBass by remember(audioSettings.bass) { mutableFloatStateOf((audioSettings.bass ?: 0).toFloat()) }
    var pendingBass by remember { mutableStateOf<Job?>(null) }
    fun scheduleBass(value: Int) {
        pendingBass?.cancel()
        pendingBass = scope.launch {
            delay(300)
            onSetBass(value)
        }
    }

    var localTreble by remember(audioSettings.treble) { mutableFloatStateOf((audioSettings.treble ?: 0).toFloat()) }
    var pendingTreble by remember { mutableStateOf<Job?>(null) }
    fun scheduleTreble(value: Int) {
        pendingTreble?.cancel()
        pendingTreble = scope.launch {
            delay(300)
            onSetTreble(value)
        }
    }

    GlassDialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.width(380.dp)) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Sound Settings", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                if (audioSettings.bass != null) {
                    Surface(
                        color = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text("Bass: ${localBass.roundToInt()}", color = Color.White, fontSize = 16.sp)
                            Slider(
                                value = localBass,
                                onValueChange = {
                                    localBass = it
                                    scheduleBass(it.roundToInt())
                                },
                                valueRange = -10f..10f,
                                steps = 19,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = Color.White.copy(alpha = 0.6f),
                                    inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                                )
                            )
                        }
                    }
                }

                if (audioSettings.treble != null) {
                    Surface(
                        color = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text("Treble: ${localTreble.roundToInt()}", color = Color.White, fontSize = 16.sp)
                            Slider(
                                value = localTreble,
                                onValueChange = {
                                    localTreble = it
                                    scheduleTreble(it.roundToInt())
                                },
                                valueRange = -10f..10f,
                                steps = 19,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = Color.White.copy(alpha = 0.6f),
                                    inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                                )
                            )
                        }
                    }
                }

                val hasNightMode = audioSettings.nightMode != null
                val hasDialogMode = audioSettings.dialogMode != null
                val hasLoudness = audioSettings.loudness != null
                val hasSubToggle = audioSettings.subEnabled != null

                if (hasNightMode || hasDialogMode || hasLoudness || hasSubToggle) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (hasNightMode) {
                            val active = audioSettings.nightMode == true
                            SoundToggleButton(
                                icon = Icons.Default.Bedtime,
                                contentDescription = "Night Sound",
                                active = active,
                                activeColor = Color(0xFF6C63FF),
                                activeIconTint = Color(0xFFD0CBFF),
                                onClick = onToggleNightMode
                            )
                        }

                        if (hasDialogMode) {
                            val active = audioSettings.dialogMode == true
                            SoundToggleButton(
                                icon = Icons.Default.RecordVoiceOver,
                                contentDescription = "Speech Enhancement",
                                active = active,
                                activeColor = SonosMuteGreen,
                                activeIconTint = Color(0xFFB9F6B4),
                                onClick = onToggleDialogMode
                            )
                        }

                        if (hasLoudness) {
                            val active = audioSettings.loudness == true
                            SoundToggleButton(
                                icon = Icons.Default.GraphicEq,
                                contentDescription = "Loudness",
                                active = active,
                                activeColor = Color(0xFFFFA726),
                                activeIconTint = Color(0xFFFFE0B2),
                                onClick = { onSetLoudness(!active) }
                            )
                        }

                        if (hasSubToggle) {
                            val active = audioSettings.subEnabled == true
                            SoundToggleButton(
                                icon = Icons.Default.Speaker,
                                contentDescription = "Subwoofer",
                                active = active,
                                activeColor = Color(0xFFEF5350),
                                activeIconTint = Color(0xFFFFCDD2),
                                onClick = onToggleSubEnabled
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SoundToggleButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    activeColor: Color,
    activeIconTint: Color,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(60.dp)
                .background(
                    if (active) activeColor.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.08f),
                    CircleShape
                )
        ) {
            Icon(icon, contentDescription = contentDescription, tint = if (active) activeIconTint else Color.White)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(contentDescription, color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
    }
}


// A track as it was when tapped - so the details pop-up keeps showing that
// track even if the song changes (and "previous" moves on) while it's open.
data class TrackDetails(
    val title: String,
    val artist: String,
    val album: String,
    val artUrl: String
)

fun TrackState.previousTrackDetails() = TrackDetails(
    title = prevTrack,
    artist = prevArtist,
    album = prevAlbum,
    artUrl = prevArtUrl
)

// The Sonos queue - usually the album playing - with each song's length
// when the service gives one. Tapping a song plays it. The playing song is
// highlighted and scrolled to; currentTitle re-reads the queue as the album
// moves on while the list is open, so that stays right.
@Composable
fun QueueDialog(
    heading: String,
    currentTitle: String,
    loadQueue: suspend () -> SonosQueue?,
    onPlayTrack: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var queue by remember { mutableStateOf<SonosQueue?>(null) }
    var loading by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    var scrolledToCurrent by remember { mutableStateOf(false) }
    // Read again when the song changes, so the highlight follows along.
    LaunchedEffect(currentTitle) {
        queue = loadQueue()
        loading = false
        val current = queue?.currentIndex ?: -1
        if (!scrolledToCurrent && current > 0) listState.scrollToItem((current - 2).coerceAtLeast(0))
        scrolledToCurrent = true
    }

    GlassDialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.width(560.dp)) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        heading,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
                Spacer(Modifier.height(8.dp))

                val tracks = queue?.tracks.orEmpty()
                when {
                    loading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                    tracks.isEmpty() -> Text(
                        if (queue == null) "Couldn't read the queue from the speaker." else "The queue is empty.",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 16.sp,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                    else -> {
                        // Show the album artist once, under the heading,
                        // unless the songs are by different artists.
                        val oneArtist = tracks.map { it.artist }.distinct().singleOrNull()
                        LazyColumn(state = listState, modifier = Modifier.heightIn(max = 480.dp)) {
                            itemsIndexed(tracks) { index, track ->
                                val isCurrent = index == queue?.currentIndex
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (isCurrent) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                                        .clickable { onPlayTrack(index) }
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Box(Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
                                        if (isCurrent) {
                                            Icon(Icons.Default.GraphicEq, contentDescription = "Playing", tint = Color.White, modifier = Modifier.size(20.dp))
                                        } else {
                                            Text("${index + 1}", color = Color.White.copy(alpha = 0.5f), fontSize = 15.sp)
                                        }
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            track.title,
                                            color = Color.White,
                                            fontSize = 17.sp,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (oneArtist == null && track.artist.isNotBlank()) {
                                            Text(
                                                track.artist,
                                                color = Color.White.copy(alpha = 0.6f),
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    if (track.duration.isNotBlank()) {
                                        Spacer(Modifier.width(12.dp))
                                        Text(track.duration, color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Glass pop-up with a track's album art, song title, artist and album -
// opened by tapping Previous on Now Playing or Cover Flow.
@Composable
fun TrackDetailsDialog(
    heading: String,
    track: TrackDetails,
    onDismiss: () -> Unit
) {
    GlassDialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.width(380.dp)) {
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
                    Text(heading, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                Spacer(Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .size(300.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (track.artUrl.isNotBlank()) {
                        AsyncImage(
                            model = track.artUrl,
                            contentDescription = "Album Cover",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(64.dp)
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                Text(
                    text = track.title,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (track.artist.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = track.artist,
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 17.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (track.album.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = track.album,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
