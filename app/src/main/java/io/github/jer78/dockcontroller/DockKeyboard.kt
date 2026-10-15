package io.github.jer78.dockcontroller

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// On-screen keys drawn by the app itself rather than the system keyboard,
// which can't be relied on inside the screensaver's window - used for
// Cover Flow search and for lock/alarm codes.

@Composable
private fun Key(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(
        color = Color.White.copy(alpha = 0.1f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

// A phone-style number pad with the code shown above it as dots.
@Composable
fun PinPad(
    code: String,
    onCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    maxLength: Int = 12,
    keySize: Dp = 64.dp
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
    ) {
        Text(
            if (code.isEmpty()) "Enter code" else "●".repeat(code.length),
            color = Color.White.copy(alpha = if (code.isEmpty()) 0.5f else 1f),
            fontSize = if (code.isEmpty()) 16.sp else 22.sp,
            letterSpacing = if (code.isEmpty()) 0.sp else 4.sp,
            modifier = Modifier.height(32.dp)
        )
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("C", "0", "<"))
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { key ->
                    Key(
                        modifier = Modifier.size(keySize),
                        onClick = {
                            when (key) {
                                "C" -> onCodeChange("")
                                "<" -> onCodeChange(code.dropLast(1))
                                else -> if (code.length < maxLength) onCodeChange(code + key)
                            }
                        }
                    ) {
                        when (key) {
                            "<" -> Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Delete", tint = Color.White)
                            "C" -> Text("Clear", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                            else -> Text(key, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

// A compact QWERTY keyboard with a number row, space and backspace.
@Composable
fun DockKeyboard(
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
    keyHeight: Dp = 48.dp
) {
    val rows = listOf("1234567890", "QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
    ) {
        rows.forEachIndexed { i, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { char ->
                    Key(modifier = Modifier.width(56.dp).height(keyHeight), onClick = { onKey(char) }) {
                        Text(char.toString(), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (i == rows.lastIndex) {
                    Key(modifier = Modifier.width(118.dp).height(keyHeight), onClick = onBackspace) {
                        Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Delete", tint = Color.White)
                    }
                }
            }
        }
        Key(modifier = Modifier.width(360.dp).height(keyHeight), onClick = { onKey(' ') }) {
            Text("space", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
        }
    }
}
