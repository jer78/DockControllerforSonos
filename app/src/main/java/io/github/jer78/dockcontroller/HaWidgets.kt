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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val CoverOpenColor = Color(0xFF64B5F6)

// Now Playing's top-left row of Home Assistant widgets, in the positions
// picked in Settings. Drawn by the entity's actual state type, so a light
// typed into a "Climate" position still shows as a light.
@Composable
fun HaWidgetRow(haState: HAState, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        haState.widgets.forEach { slot ->
            when (val entity = haState.entities[slot.entityId]) {
                is HaClimateState -> ClimateWidget(entity) { onOpen(entity.entityId) }
                is HaLightState -> LightWidget(entity) { onOpen(entity.entityId) }
                is HaCoverState -> CoverWidget(entity) { onOpen(entity.entityId) }
                is HaLockState -> LockWidget(entity) { onOpen(entity.entityId) }
                null -> {}
            }
        }
    }
}

// The pop-up for whichever widget was tapped.
@Composable
fun HaWidgetDialog(entity: HaEntityState, onHaCommand: (HaCommand) -> Unit, onDismiss: () -> Unit) {
    val id = entity.entityId
    when (entity) {
        is HaClimateState -> ThermostatDialog(
            climate = entity,
            onSetTemp = { temp, high, low -> onHaCommand(HaCommand.SetClimateTemperature(id, temp, high, low)) },
            onSetMode = { onHaCommand(HaCommand.SetHvacMode(id, it)) },
            onSetFan = { onHaCommand(HaCommand.SetFanMode(id, it)) },
            onSetPreset = { onHaCommand(HaCommand.SetPresetMode(id, it)) },
            onDismiss = onDismiss
        )
        is HaLightState -> LightDialog(
            light = entity,
            onSetOn = { onHaCommand(HaCommand.SetLightOn(id, it)) },
            onSetBrightness = { onHaCommand(HaCommand.SetLightBrightness(id, it)) },
            onSetColorRgb = { r, g, b -> onHaCommand(HaCommand.SetLightRgb(id, r, g, b)) },
            onSetColorTemp = { onHaCommand(HaCommand.SetLightColorTemp(id, it)) },
            onDismiss = onDismiss
        )
        is HaCoverState -> CoverDialog(cover = entity, onHaCommand = onHaCommand, onDismiss = onDismiss)
        is HaLockState -> LockConfirmDialog(lock = entity, onHaCommand = onHaCommand, onDismiss = onDismiss)
    }
}

private val ClimateHeatColor = Color(0xFFFF4D3D)
private val ClimateCoolColor = Color(0xFF4FC3F7)

// Icon and color follow what the system is doing: a red flame for heat, a
// blue snowflake for cool (in heat/cool or auto mode, whichever it's
// actively doing). While HA reports it actually running (hvac_action), the
// chip "breathes" - a slow glow in that color with the icon swelling
// slightly - and goes still once the room reaches its target.
@Composable
private fun ClimateWidget(climate: HaClimateState, onClick: () -> Unit) {
    val mode = when (climate.hvacAction) {
        "heating", "preheating" -> "heat"
        "cooling" -> "cool"
        else -> climate.hvacState
    }
    val (icon, tint) = when (mode) {
        "heat" -> Icons.Default.LocalFireDepartment to ClimateHeatColor
        "cool" -> Icons.Default.AcUnit to ClimateCoolColor
        "dry" -> Icons.Default.WaterDrop to Color(0xFFFFC107)
        "fan_only" -> Icons.Default.Air to Color.White
        else -> Icons.Default.Thermostat to Color.White
    }
    val isRunning = climate.hvacAction in setOf("heating", "preheating", "cooling", "drying", "fan")

    val breath = if (isRunning) {
        val transition = rememberInfiniteTransition(label = "climateRunning")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "climateBreath"
        )
        value
    } else 0f

    Surface(
        color = lerp(Color.Black.copy(alpha = 0.4f), tint.copy(alpha = 0.35f), breath),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = if (isRunning) "${climate.name}: ${climate.hvacAction}" else climate.name,
                tint = tint,
                modifier = Modifier.scale(1f + 0.12f * breath)
            )

            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "${climate.climateTemp} • ${climate.hvacState.uppercase()}",
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun LightWidget(light: HaLightState, onClick: () -> Unit) {
    Surface(
        color = if (light.isOn) Color.Yellow.copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.4f),
        shape = CircleShape,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.padding(10.dp)) {
            Icon(
                Icons.Default.Lightbulb,
                contentDescription = light.name,
                tint = if (light.isOn) Color.Black else Color.White
            )
        }
    }
}

// Lit up blue while open (or partly open), pulsing while it moves.
@Composable
private fun CoverWidget(cover: HaCoverState, onClick: () -> Unit) {
    val iconAlpha = if (cover.isMoving) {
        val pulse = rememberInfiniteTransition(label = "coverMoving")
        val alpha by pulse.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
            label = "coverMovingAlpha"
        )
        alpha
    } else 1f
    Surface(
        color = if (cover.isOpen) CoverOpenColor.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.4f),
        shape = CircleShape,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.padding(10.dp)) {
            Icon(
                coverIcon(cover.deviceClass, cover.isOpen),
                contentDescription = cover.name,
                tint = (if (cover.isOpen) Color.Black else Color.White).copy(alpha = iconAlpha)
            )
        }
    }
}

private val LockUnlockedColor = Color(0xFFFFB300)
private val LockLockedColor = Color(0xFF66BB6A)
private val LockJammedColor = Color(0xFFE53935)

private fun lockIcon(lock: HaLockState): ImageVector = when {
    lock.isJammed -> Icons.Default.Warning
    lock.isLocked || lock.state == "locking" -> Icons.Default.Lock
    else -> Icons.Default.LockOpen
}

private fun lockStatusText(lock: HaLockState): String = when (lock.state) {
    "locked" -> "Locked"
    "unlocked" -> "Unlocked"
    "locking" -> "Locking..."
    "unlocking" -> "Unlocking..."
    "opening" -> "Opening..."
    "open" -> "Open"
    "jammed" -> "Jammed"
    else -> lock.state.replaceFirstChar { it.uppercase() }
}

// Quiet while locked; amber while unlocked (the state worth noticing), red
// if jammed, pulsing while it's locking or unlocking.
@Composable
private fun LockWidget(lock: HaLockState, onClick: () -> Unit) {
    val iconAlpha = if (lock.isMoving) {
        val pulse = rememberInfiniteTransition(label = "lockMoving")
        val alpha by pulse.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
            label = "lockMovingAlpha"
        )
        alpha
    } else 1f
    val unlocked = lock.state in setOf("unlocked", "unlocking", "open", "opening")
    val (background, tint) = when {
        lock.isJammed -> LockJammedColor.copy(alpha = 0.85f) to Color.White
        unlocked -> LockUnlockedColor.copy(alpha = 0.85f) to Color.Black
        else -> Color.Black.copy(alpha = 0.4f) to Color.White
    }
    Surface(
        color = background,
        shape = CircleShape,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.padding(10.dp)) {
            Icon(
                lockIcon(lock),
                contentDescription = "${lock.name}: ${lockStatusText(lock)}",
                tint = tint.copy(alpha = iconAlpha)
            )
        }
    }
}

// Tapping a lock widget never acts straight away - this pop-up is the
// confirmation, naming the lock and what will happen, and only the
// Lock/Unlock button sends anything to Home Assistant.
@Composable
fun LockConfirmDialog(lock: HaLockState, onHaCommand: (HaCommand) -> Unit, onDismiss: () -> Unit) {
    // Jammed or unknown locks are offered Lock, the safe direction.
    val willUnlock = lock.isLocked
    val actionLabel = if (willUnlock) "Unlock" else "Lock"
    val actionColor = if (willUnlock) LockUnlockedColor else LockLockedColor
    val stateColor = when {
        lock.isJammed -> LockJammedColor
        lock.isLocked -> LockLockedColor
        else -> LockUnlockedColor
    }
    val canAct = !lock.requiresCode && !lock.isMoving && lock.state != "unavailable"

    GlassDialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.width(380.dp)) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(96.dp)
                        .background(stateColor.copy(alpha = 0.2f), CircleShape)
                ) {
                    Icon(lockIcon(lock), contentDescription = null, tint = stateColor, modifier = Modifier.size(52.dp))
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        lock.name,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(lockStatusText(lock), color = stateColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }

                Text(
                    when {
                        lock.requiresCode -> "This lock needs a code, which isn't supported here yet. Use the Home Assistant app instead."
                        lock.isMoving -> "Wait for the lock to finish before changing it."
                        lock.state == "unavailable" -> "Home Assistant can't reach this lock right now."
                        else -> "$actionLabel ${lock.name}?"
                    },
                    color = Color.White.copy(alpha = if (canAct) 1f else 0.7f),
                    fontSize = if (canAct) 18.sp else 14.sp,
                    fontWeight = if (canAct) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        color = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).clickable(onClick = onDismiss)
                    ) {
                        Text(
                            if (canAct) "Cancel" else "Close",
                            color = Color.White,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    }
                    if (canAct) {
                        Surface(
                            color = actionColor,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(20.dp))
                                .clickable {
                                    onHaCommand(if (willUnlock) HaCommand.Unlock(lock.entityId) else HaCommand.Lock(lock.entityId))
                                    onDismiss()
                                }
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 14.dp)
                            ) {
                                Icon(
                                    if (willUnlock) Icons.Default.LockOpen else Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(actionLabel, color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// Picks an icon for HA's cover device_class, with an open and closed form
// where Material has one (blinds, shades, curtains, doors). The rest use
// the same icon either way - the widget's color shows open/closed.
fun coverIcon(deviceClass: String, isOpen: Boolean): ImageVector = when (deviceClass) {
    "blind" -> if (isOpen) Icons.Default.Blinds else Icons.Default.BlindsClosed
    "shade", "shutter" -> if (isOpen) Icons.Default.RollerShades else Icons.Default.RollerShadesClosed
    "curtain" -> if (isOpen) Icons.Default.Curtains else Icons.Default.CurtainsClosed
    "garage" -> Icons.Default.Garage
    "door" -> if (isOpen) Icons.Default.MeetingRoom else Icons.Default.DoorFront
    "gate" -> Icons.Default.Fence
    "awning" -> Icons.Default.Storefront
    "damper" -> Icons.Default.Air
    "window" -> Icons.Default.Window
    else -> if (isOpen) Icons.Default.Blinds else Icons.Default.BlindsClosed
}

private fun coverStatusText(cover: HaCoverState): String {
    val state = when (cover.state) {
        "opening" -> "Opening"
        "closing" -> "Closing"
        "open" -> "Open"
        "closed" -> "Closed"
        else -> cover.state.replaceFirstChar { it.uppercase() }
    }
    val position = cover.position
    return if (position != null && cover.state != "closed" && cover.state != "unavailable") "$state · $position%" else state
}

@Composable
fun CoverDialog(cover: HaCoverState, onHaCommand: (HaCommand) -> Unit, onDismiss: () -> Unit) {
    val id = cover.entityId
    val scope = rememberCoroutineScope()
    var localPosition by remember(cover.position) { mutableFloatStateOf((cover.position ?: 0).toFloat()) }
    var pendingPosition by remember { mutableStateOf<Job?>(null) }

    fun schedulePosition(value: Int) {
        pendingPosition?.cancel()
        pendingPosition = scope.launch {
            delay(400)
            onHaCommand(HaCommand.SetCoverPosition(id, value))
        }
    }

    // An integration that reports no features at all is treated as
    // supporting open/close, rather than showing a dialog with no buttons.
    val features = cover.supportedFeatures
    val canOpen = features == 0 || features and COVER_FEATURE_OPEN != 0
    val canClose = features == 0 || features and COVER_FEATURE_CLOSE != 0

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
                    Text(
                        cover.name,
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

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(96.dp)
                            .background(
                                if (cover.isOpen) CoverOpenColor.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f),
                                CircleShape
                            )
                    ) {
                        Icon(
                            coverIcon(cover.deviceClass, cover.isOpen),
                            contentDescription = null,
                            tint = if (cover.isOpen) CoverOpenColor else Color.White,
                            modifier = Modifier.size(52.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(coverStatusText(cover), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                }

                if (cover.supportsPosition) {
                    Surface(
                        color = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Position", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(10.dp))
                            LargeVolumeSlider(
                                value = localPosition,
                                onValueChange = {
                                    localPosition = it
                                    schedulePosition(it.roundToInt())
                                },
                                fillColor = CoverOpenColor,
                                height = 64.dp,
                                fontSize = 20.sp,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (canOpen) {
                        CoverActionButton(Icons.Default.KeyboardArrowUp, "Open", Modifier.weight(1f)) {
                            onHaCommand(HaCommand.OpenCover(id))
                        }
                    }
                    if (cover.supportsStop) {
                        CoverActionButton(Icons.Default.Stop, "Stop", Modifier.weight(1f)) {
                            onHaCommand(HaCommand.StopCover(id))
                        }
                    }
                    if (canClose) {
                        CoverActionButton(Icons.Default.KeyboardArrowDown, "Close", Modifier.weight(1f)) {
                            onHaCommand(HaCommand.CloseCover(id))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverActionButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        color = Color.White.copy(alpha = 0.08f),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 14.dp)
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
            Text(label, color = Color.White, fontSize = 14.sp)
        }
    }
}
