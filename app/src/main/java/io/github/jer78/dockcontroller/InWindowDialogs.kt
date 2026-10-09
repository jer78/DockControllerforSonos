package io.github.jer78.dockcontroller

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// Pop-ups inside the screensaver. Compose's Dialog opens a separate window,
// and a DreamService doesn't reliably show those or send them taps - so in
// SonosDreamService pop-ups are drawn as an overlay inside the dream's own
// window instead. Everywhere else (no host provided) they're normal Dialogs.
class InWindowDialogHost {
    internal class Entry(
        onDismissRequest: () -> Unit,
        properties: DialogProperties,
        locals: CompositionLocalContext,
        content: @Composable () -> Unit
    ) {
        var onDismissRequest by mutableStateOf(onDismissRequest)
        var properties by mutableStateOf(properties)
        var locals by mutableStateOf(locals)
        var content by mutableStateOf(content)
    }

    // In opening order, so a pop-up opened from another one draws on top.
    internal val entries = mutableStateListOf<Entry>()

    val isShowingDialog: Boolean get() = entries.isNotEmpty()

    // The dream's Back key - closes the top pop-up instead of the screensaver.
    fun dismissTop() {
        val top = entries.lastOrNull() ?: return
        if (top.properties.dismissOnBackPress) top.onDismissRequest()
    }
}

val LocalInWindowDialogHost = staticCompositionLocalOf<InWindowDialogHost?> { null }

// The dream blurs its own screen content while a pop-up is open (Android 12+),
// standing in for the cross-window blur a real dialog window gets.
val inWindowBlurSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun DockDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit
) {
    val host = LocalInWindowDialogHost.current
    if (host == null) {
        Dialog(onDismissRequest = onDismissRequest, properties = properties, content = content)
        return
    }

    // Carried over so the content sees the same theme/locals as where it was
    // declared, even though it's drawn up at the dream's root.
    val locals = currentCompositionLocalContext
    val entry = remember(host) { InWindowDialogHost.Entry(onDismissRequest, properties, locals, content) }
    SideEffect {
        entry.onDismissRequest = onDismissRequest
        entry.properties = properties
        entry.locals = locals
        entry.content = content
    }
    DisposableEffect(host, entry) {
        host.entries.add(entry)
        onDispose { host.entries.remove(entry) }
    }
}

// Drawn last at the dream's root, above everything else on screen.
@Composable
fun InWindowDialogLayer(host: InWindowDialogHost) {
    host.entries.forEach { entry ->
        key(entry) {
            val properties = entry.properties
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (inWindowBlurSupported) 0.2f else 0.5f))
                    .pointerInput(entry) {
                        detectTapGestures {
                            if (entry.properties.dismissOnClickOutside) entry.onDismissRequest()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = (if (properties.usePlatformDefaultWidth) {
                        Modifier.padding(horizontal = 40.dp, vertical = 24.dp).widthIn(max = 560.dp)
                    } else {
                        Modifier.padding(vertical = 24.dp)
                    })
                        // Taps on the pop-up itself mustn't reach the scrim
                        // behind it and dismiss it.
                        .pointerInput(Unit) { detectTapGestures { } }
                ) {
                    CompositionLocalProvider(entry.locals) {
                        entry.content()
                    }
                }
            }
        }
    }
}

// Material's AlertDialog, or an in-window look-alike inside the screensaver.
@Composable
fun DockAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties()
) {
    if (LocalInWindowDialogHost.current == null) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            title = title,
            text = text,
            properties = properties
        )
        return
    }

    DockDialog(onDismissRequest = onDismissRequest, properties = properties) {
        Surface(
            modifier = modifier.sizeIn(minWidth = 280.dp, maxWidth = 560.dp),
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation
        ) {
            Column(Modifier.padding(24.dp)) {
                title?.let {
                    CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.titleContentColor) {
                        ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                            Box(Modifier.padding(bottom = 16.dp)) { it() }
                        }
                    }
                }
                text?.let {
                    CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.textContentColor) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                            Box(Modifier.weight(1f, fill = false).padding(bottom = 24.dp)) { it() }
                        }
                    }
                }
                Row(
                    modifier = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}
