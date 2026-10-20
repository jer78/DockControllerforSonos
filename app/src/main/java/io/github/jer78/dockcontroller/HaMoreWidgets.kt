package io.github.jer78.dockcontroller

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Sensor, binary sensor, fan and alarm panel widgets. Their default icons
// are HA's own (Material Design Icons names), so they match what Home
// Assistant shows; an icon set on the entity in HA takes their place.

// ---- Sensor ----

private fun sensorDefaultIcon(deviceClass: String): String = when (deviceClass) {
    "temperature" -> "thermometer"
    "humidity", "moisture" -> "water-percent"
    "power", "apparent_power", "reactive_power" -> "flash"
    "energy" -> "lightning-bolt"
    "battery" -> "battery"
    "illuminance" -> "brightness-5"
    "pm1", "pm10", "pm25", "aqi", "volatile_organic_compounds", "nitrogen_dioxide", "ozone" -> "air-filter"
    "carbon_dioxide" -> "molecule-co2"
    "carbon_monoxide" -> "molecule-co"
    "pressure", "atmospheric_pressure" -> "gauge"
    "voltage" -> "sine-wave"
    "current" -> "current-ac"
    "wind_speed" -> "weather-windy"
    "speed" -> "speedometer"
    "precipitation", "precipitation_intensity" -> "weather-rainy"
    "signal_strength" -> "wifi"
    "gas" -> "meter-gas"
    "water" -> "water"
    "monetary" -> "cash"
    "timestamp" -> "clock-outline"
    "duration" -> "timer-outline"
    "distance" -> "arrow-left-right"
    "weight" -> "weight"
    else -> "eye"
}

// 21.4667 -> "21.5", 1500 -> "1500", "Clear" stays as it is.
private fun sensorValueText(sensor: HaSensorState): String {
    if (sensor.state == "unavailable" || sensor.state == "unknown") return "—"
    val number = sensor.state.toDoubleOrNull()
    val value = if (number == null) sensor.state else {
        val rounded = Math.round(number * 10) / 10.0
        if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
    }
    return when {
        sensor.unit.isBlank() -> value
        sensor.unit == "%" -> "$value%"
        else -> "$value ${sensor.unit}"
    }
}

// Shows the reading itself, like the climate chip; nothing to control, so
// no pop-up.
@Composable
fun SensorWidget(sensor: HaSensorState) {
    GlassSurface(
        fill = Color.Transparent,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.semantics { contentDescription = "${sensor.name}: ${sensorValueText(sensor)}" }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MdiIcons.Icon(
                listOfNotNull(sensor.icon, sensorDefaultIcon(sensor.deviceClass)),
                tint = Color.White,
                fallback = Icons.Default.Sensors
            )
            Spacer(Modifier.width(6.dp))
            Text(sensorValueText(sensor), color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---- Binary sensor ----

private val BinaryAlertColor = Color(0xFFE53935)
private val BinaryAttentionColor = Color(0xFFFFB300)
private val BinaryGoodColor = Color(0xFF66BB6A)

// HA's icons for each device class, on and off.
private fun binarySensorDefaultIcon(sensor: HaBinarySensorState): String {
    val on = sensor.isOn
    return when (sensor.deviceClass) {
        "door" -> if (on) "door-open" else "door-closed"
        "garage_door" -> if (on) "garage-open" else "garage"
        "window" -> if (on) "window-open" else "window-closed"
        "opening" -> if (on) "square-outline" else "square"
        "motion" -> if (on) "motion-sensor" else "motion-sensor-off"
        "occupancy", "presence" -> if (on) "home" else "home-outline"
        "moisture" -> if (on) "water" else "water-off"
        "smoke" -> if (on) "smoke-detector-variant-alert" else "smoke-detector-variant"
        "carbon_monoxide" -> if (on) "smoke-detector-alert" else "smoke-detector"
        "heat" -> if (on) "fire" else "thermometer"
        "cold" -> if (on) "snowflake" else "thermometer"
        "lock" -> if (on) "lock-open" else "lock"
        "battery" -> if (on) "battery-outline" else "battery"
        "battery_charging" -> if (on) "battery-charging" else "battery"
        "connectivity" -> if (on) "check-network-outline" else "close-network-outline"
        "plug", "power" -> if (on) "power-plug" else "power-plug-off"
        "light" -> if (on) "brightness-7" else "brightness-5"
        "sound" -> if (on) "music-note" else "music-note-off"
        "vibration" -> if (on) "vibrate" else "crop-portrait"
        "running" -> if (on) "play" else "stop"
        "problem", "safety", "tamper", "gas" -> if (on) "alert-circle" else "check-circle"
        "update" -> if (on) "package-up" else "package"
        else -> if (on) "checkbox-marked-circle" else "radiobox-blank"
    }
}

private fun binarySensorStateText(sensor: HaBinarySensorState): String {
    if (sensor.state == "unavailable" || sensor.state == "unknown") return sensor.state.replaceFirstChar { it.uppercase() }
    val on = sensor.isOn
    return when (sensor.deviceClass) {
        "door", "garage_door", "window", "opening" -> if (on) "Open" else "Closed"
        "motion", "occupancy", "moisture", "smoke", "carbon_monoxide", "gas", "sound", "vibration", "tamper" -> if (on) "Detected" else "Clear"
        "presence" -> if (on) "Home" else "Away"
        "connectivity" -> if (on) "Connected" else "Disconnected"
        "lock" -> if (on) "Unlocked" else "Locked"
        "battery" -> if (on) "Low" else "Normal"
        "battery_charging" -> if (on) "Charging" else "Not charging"
        "problem" -> if (on) "Problem" else "OK"
        "safety" -> if (on) "Unsafe" else "Safe"
        "heat" -> if (on) "Hot" else "Normal"
        "cold" -> if (on) "Cold" else "Normal"
        "running" -> if (on) "Running" else "Not running"
        "update" -> if (on) "Update available" else "Up to date"
        else -> if (on) "On" else "Off"
    }
}

// Lit while on: red for hazards, green where on is the normal, healthy
// state (connected, powered, home), amber for everything else worth a look
// (a door left open, motion).
private fun binarySensorOnColor(deviceClass: String): Color = when (deviceClass) {
    "smoke", "carbon_monoxide", "gas", "moisture", "problem", "safety", "tamper", "heat", "cold", "battery" -> BinaryAlertColor
    "connectivity", "plug", "power", "running", "light", "presence", "battery_charging" -> BinaryGoodColor
    else -> BinaryAttentionColor
}

@Composable
fun BinarySensorWidget(sensor: HaBinarySensorState) {
    val background = if (sensor.isOn) binarySensorOnColor(sensor.deviceClass).copy(alpha = 0.85f) else Color.Transparent
    val tint = if (sensor.isOn) Color.Black else Color.White.copy(alpha = if (sensor.state == "unavailable") 0.4f else 1f)
    GlassSurface(
        fill = background,
        shape = CircleShape,
        modifier = Modifier.semantics { contentDescription = "${sensor.name}: ${binarySensorStateText(sensor)}" }
    ) {
        Box(modifier = Modifier.padding(10.dp)) {
            MdiIcons.Icon(
                listOfNotNull(sensor.icon, binarySensorDefaultIcon(sensor)),
                tint = tint,
                fallback = Icons.Default.Sensors
            )
        }
    }
}

// ---- Fan ----

private val FanOnColor = Color(0xFF80DEEA)

// The fan icon spins while it's on, faster at higher speeds.
@Composable
private fun SpinningFanIcon(fan: HaFanState, tint: Color, size: androidx.compose.ui.unit.Dp) {
    val rotation = if (fan.isOn) {
        val speed = (fan.percentage ?: 50).coerceIn(10, 100)
        val transition = rememberInfiniteTransition(label = "fanSpin")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween((3000 - speed * 24), easing = LinearEasing)),
            label = "fanAngle"
        )
        angle
    } else 0f
    MdiIcons.Icon(
        listOfNotNull(fan.icon, if (fan.isOn) "fan" else "fan-off"),
        tint = tint,
        size = size,
        fallback = Icons.Default.Air,
        modifier = Modifier.rotate(rotation)
    )
}

@Composable
fun FanWidget(fan: HaFanState, onClick: () -> Unit) {
    GlassSurface(
        fill = if (fan.isOn) FanOnColor.copy(alpha = 0.85f) else Color.Transparent,
        shape = CircleShape,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "${fan.name}: ${if (fan.isOn) "On" else "Off"}" }
    ) {
        Box(modifier = Modifier.padding(10.dp)) {
            SpinningFanIcon(fan, tint = if (fan.isOn) Color.Black else Color.White, size = 24.dp)
        }
    }
}

@Composable
private fun DialogHeader(title: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
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
}

@Composable
private fun ToggleChip(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Surface(
        color = if (selected) color else Color.White.copy(alpha = 0.08f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)
    ) {
        Text(
            label,
            color = if (selected) Color.Black else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

// On/off, then whichever of speed, oscillation, direction and presets the
// fan supports.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FanDialog(fan: HaFanState, onHaCommand: (HaCommand) -> Unit, onDismiss: () -> Unit) {
    val id = fan.entityId
    val scope = rememberCoroutineScope()
    var localPercentage by remember(fan.percentage) { mutableFloatStateOf((fan.percentage ?: 0).toFloat()) }
    var pending by remember { mutableStateOf<Job?>(null) }

    fun scheduleSpeed(value: Float) {
        // Snap to the fan's own steps, so a 3-speed fan lands on 33/67/100.
        val step = fan.percentageStep
        val snapped = (Math.round(value / step) * step).roundToInt().coerceIn(0, 100)
        pending?.cancel()
        pending = scope.launch {
            delay(400)
            onHaCommand(HaCommand.SetFanPercentage(id, snapped))
        }
    }

    GlassDialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.width(400.dp)) {
            Column(
                modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                DialogHeader(fan.name, onDismiss)

                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(96.dp)
                            .background(if (fan.isOn) FanOnColor.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f), CircleShape)
                            .clip(CircleShape)
                            .clickable { onHaCommand(HaCommand.SetFanOn(id, !fan.isOn)) }
                    ) {
                        SpinningFanIcon(fan, tint = if (fan.isOn) FanOnColor else Color.White, size = 52.dp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        when {
                            !fan.isOn -> "Off · tap to turn on"
                            fan.percentage != null -> "On · ${fan.percentage}%"
                            else -> "On"
                        },
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )
                }

                if (fan.supports(FAN_FEATURE_SET_SPEED)) {
                    Surface(color = Color.White.copy(alpha = 0.08f), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Speed", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(10.dp))
                            LargeVolumeSlider(
                                value = localPercentage,
                                onValueChange = {
                                    localPercentage = it
                                    scheduleSpeed(it)
                                },
                                fillColor = FanOnColor,
                                height = 64.dp,
                                fontSize = 20.sp,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                val showOscillate = fan.supports(FAN_FEATURE_OSCILLATE)
                val showDirection = fan.supports(FAN_FEATURE_DIRECTION)
                if (showOscillate || showDirection) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (showOscillate) {
                            ToggleChip("Oscillate", fan.oscillating == true, FanOnColor) {
                                onHaCommand(HaCommand.SetFanOscillating(id, fan.oscillating != true))
                            }
                        }
                        if (showDirection) {
                            ToggleChip("Forward", fan.direction == "forward", FanOnColor) {
                                onHaCommand(HaCommand.SetFanDirection(id, "forward"))
                            }
                            ToggleChip("Reverse", fan.direction == "reverse", FanOnColor) {
                                onHaCommand(HaCommand.SetFanDirection(id, "reverse"))
                            }
                        }
                    }
                }

                if (fan.supports(FAN_FEATURE_PRESET_MODE) && fan.presetModes.isNotEmpty()) {
                    Text("Preset", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        fan.presetModes.forEach { mode ->
                            ToggleChip(mode.replace('_', ' ').replaceFirstChar { it.uppercase() }, fan.presetMode == mode, FanOnColor) {
                                onHaCommand(HaCommand.SetFanPreset(id, mode))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- Alarm panel ----

private val AlarmArmedColor = Color(0xFF66BB6A)
private val AlarmPendingColor = Color(0xFFFFB300)
private val AlarmTriggeredColor = Color(0xFFE53935)

private fun alarmIconName(state: String): String = when (state) {
    "disarmed" -> "shield-off"
    "armed_home" -> "shield-home"
    "armed_away" -> "shield-lock"
    "armed_night" -> "shield-moon"
    "armed_vacation" -> "shield-airplane"
    "armed_custom_bypass" -> "security"
    "arming", "disarming", "pending" -> "shield-sync"
    "triggered" -> "bell-ring"
    else -> "shield-outline"
}

private fun alarmStateText(state: String): String = when (state) {
    "disarmed" -> "Disarmed"
    "armed_home" -> "Armed home"
    "armed_away" -> "Armed away"
    "armed_night" -> "Armed night"
    "armed_vacation" -> "Armed vacation"
    "armed_custom_bypass" -> "Armed custom bypass"
    "arming" -> "Arming..."
    "disarming" -> "Disarming..."
    "pending" -> "Pending..."
    "triggered" -> "Triggered!"
    else -> state.replaceFirstChar { it.uppercase() }
}

private fun alarmColor(alarm: HaAlarmState): Color? = when {
    alarm.isTriggered -> AlarmTriggeredColor
    alarm.isTransitioning -> AlarmPendingColor
    alarm.isArmed -> AlarmArmedColor
    else -> null
}

// Arm modes the panel offers, as (service suffix, label, icon). A panel
// that reports no features gets Home and Away.
private fun alarmArmModes(alarm: HaAlarmState): List<Triple<String, String, String>> {
    val all = listOf(
        Triple(ALARM_FEATURE_ARM_HOME, Triple("home", "Home", "shield-home"), true),
        Triple(ALARM_FEATURE_ARM_AWAY, Triple("away", "Away", "shield-lock"), true),
        Triple(ALARM_FEATURE_ARM_NIGHT, Triple("night", "Night", "shield-moon"), false),
        Triple(ALARM_FEATURE_ARM_VACATION, Triple("vacation", "Vacation", "shield-airplane"), false),
        Triple(ALARM_FEATURE_ARM_CUSTOM_BYPASS, Triple("custom_bypass", "Custom", "security"), false)
    )
    return all.filter { (bit, _, default) -> if (alarm.supportedFeatures == 0) default else alarm.supportedFeatures and bit != 0 }
        .map { it.second }
}

// Quiet while disarmed, green when armed, pulsing amber while arming or
// counting down, pulsing red when triggered.
@Composable
fun AlarmWidget(alarm: HaAlarmState, onClick: () -> Unit) {
    val color = alarmColor(alarm)
    val pulse = if (alarm.isTransitioning || alarm.isTriggered) {
        val transition = rememberInfiniteTransition(label = "alarmPulse")
        val value by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.45f,
            animationSpec = infiniteRepeatable(tween(if (alarm.isTriggered) 400 else 800, easing = LinearEasing), RepeatMode.Reverse),
            label = "alarmPulseAlpha"
        )
        value
    } else 1f
    GlassSurface(
        fill = color?.copy(alpha = 0.85f * pulse) ?: Color.Transparent,
        shape = CircleShape,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "${alarm.name}: ${alarmStateText(alarm.state)}" }
    ) {
        Box(modifier = Modifier.padding(10.dp)) {
            MdiIcons.Icon(
                listOfNotNull(alarm.icon, alarmIconName(alarm.state)),
                tint = if (color != null) Color.Black else Color.White,
                fallback = Icons.Default.Security
            )
        }
    }
}

// Arm (in any mode the panel supports) when disarmed, disarm otherwise. A
// panel with a code gets a number pad - or a keyboard for text codes -
// beside the buttons; arming only asks for it if the panel requires that.
// The pop-up stays open until Home Assistant answers, so a wrong code is
// reported there and can be re-entered.
@Composable
fun AlarmDialog(
    alarm: HaAlarmState,
    onHaCommandWithResult: (HaCommand, (HaServiceResult) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    val id = alarm.entityId
    val disarmed = alarm.state == "disarmed"
    val available = alarm.state != "unavailable" && alarm.state != "unknown"
    // From the screensaver over a locked tablet, disarming waits for the
    // tablet to be unlocked (see DeviceUnlockGate); arming doesn't.
    val unlockGate = LocalDeviceUnlockGate.current
    val needsTabletUnlock = !disarmed && available && unlockGate?.isLocked() == true
    val needsCode = alarm.codeFormat != null && available && (!disarmed || alarm.codeArmRequired) && !needsTabletUnlock
    val textCode = alarm.codeFormat == "text"
    var code by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<HaServiceResult?>(null) }
    var failedWithCode by remember { mutableStateOf(false) }
    val ready = (!needsCode || code.isNotEmpty()) && !sending
    val stateColor = alarmColor(alarm) ?: Color.White
    val sentCode = code.takeIf { needsCode && it.isNotEmpty() }

    fun send(command: HaCommand) {
        sending = true
        failure = null
        onHaCommandWithResult(command) { result ->
            sending = false
            if (result is HaServiceResult.Success) onDismiss() else {
                failure = result
                failedWithCode = sentCode != null
                code = ""
            }
        }
    }

    fun typed(newCode: String) {
        code = newCode
        failure = null
    }

    val info: @Composable () -> Unit = {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.width(332.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(96.dp).background(stateColor.copy(alpha = 0.2f), CircleShape)
            ) {
                MdiIcons.Icon(
                    listOfNotNull(alarm.icon, alarmIconName(alarm.state)),
                    tint = stateColor,
                    size = 52.dp,
                    fallback = Icons.Default.Security
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(alarm.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(alarmStateText(alarm.state), color = stateColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            HaCommandFeedback(failure, failedWithCode)
            if (sending) {
                Text("Checking...", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
            }
            if (!available) {
                Text(
                    "Home Assistant can't reach this alarm right now.",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            } else if (disarmed) {
                // Two buttons per row.
                alarmArmModes(alarm).chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { (mode, label, icon) ->
                            AlarmActionButton(label, icon, AlarmArmedColor, enabled = ready, modifier = Modifier.weight(1f)) {
                                send(HaCommand.ArmAlarm(id, mode, sentCode))
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else if (needsTabletUnlock) {
                Text(
                    "Unlock this tablet first to disarm ${alarm.name}.",
                    color = Color.White,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
                UnlockTabletButton(modifier = Modifier.fillMaxWidth()) { unlockGate?.requestUnlock() }
            } else {
                AlarmActionButton("Disarm", "shield-off", AlarmPendingColor, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                    send(HaCommand.DisarmAlarm(id, sentCode))
                }
            }
        }
    }

    GlassDialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.width(if (needsCode) 720.dp else 380.dp)) {
            Column(modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 24.dp)) {
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
                when {
                    !needsCode -> Box(Modifier.padding(end = 12.dp)) { info() }
                    textCode -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(end = 12.dp)
                    ) {
                        info()
                        Text(
                            if (code.isEmpty()) "Enter code" else "●".repeat(code.length),
                            color = Color.White.copy(alpha = if (code.isEmpty()) 0.5f else 1f),
                            fontSize = 20.sp
                        )
                        DockKeyboard(
                            onKey = { if (it != ' ' && code.length < 32) typed(code + it) },
                            onBackspace = { typed(code.dropLast(1)) },
                            keyHeight = 44.dp
                        )
                    }
                    else -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        info()
                        PinPad(code = code, onCodeChange = { typed(it) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AlarmActionButton(
    label: String,
    icon: String,
    color: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = color.copy(alpha = if (enabled) 1f else 0.35f),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.clip(RoundedCornerShape(20.dp)).clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 14.dp)
        ) {
            MdiIcons.Icon(listOf(icon), tint = Color.Black, size = 20.dp, fallback = Icons.Default.Security)
            Spacer(Modifier.width(8.dp))
            Text(label, color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}
