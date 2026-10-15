package io.github.jer78.dockcontroller

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Home Assistant names its icons from Material Design Icons ("mdi:garage").
// The MDI webfont (res/font/mdi_icons.ttf, @mdi/font 7.4.47) draws them, and
// assets/mdi/icons.txt maps each name to its glyph - one "name HEX" per line,
// generated from the font's CSS.
object MdiIcons {
    private val fontFamily = FontFamily(Font(R.font.mdi_icons))

    @Volatile
    private var codepoints: Map<String, Int>? = null

    // The glyph for "mdi:name" (or just "name"), or null if this font
    // version doesn't have it. Reads the name list on first use, so call
    // off the main thread.
    fun codepoint(context: Context, icon: String): Int? {
        val map = codepoints ?: synchronized(this) {
            codepoints ?: load(context).also { codepoints = it }
        }
        return map[icon.removePrefix("mdi:")]
    }

    private fun load(context: Context): Map<String, Int> = try {
        context.assets.open("mdi/icons.txt").bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val space = line.indexOf(' ')
                if (space <= 0) null else line.substring(space + 1).toIntOrNull(16)?.let { line.substring(0, space) to it }
            }.toMap(HashMap())
        }
    } catch (e: Exception) {
        Log.e("MdiIcons", "Couldn't load icon names", e)
        emptyMap()
    }

    // Draws an MDI icon by name, trying each in turn - so a custom icon HA
    // sends can fall back to the domain's default. Draws nothing until the
    // name list has loaded, or if none of the names are known.
    @Composable
    fun Icon(names: List<String>, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
        val context = LocalContext.current.applicationContext
        val codepoint by produceState<Int?>(null, names) {
            value = withContext(Dispatchers.IO) { names.firstNotNullOfOrNull { codepoint(context, it) } }
        }
        val fontSize = with(LocalDensity.current) { size.toSp() }
        Box(contentAlignment = Alignment.Center, modifier = modifier.size(size)) {
            codepoint?.let {
                Text(
                    String(Character.toChars(it)),
                    color = tint,
                    style = TextStyle(
                        fontFamily = fontFamily,
                        fontSize = fontSize,
                        lineHeight = fontSize,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    )
                )
            }
        }
    }
}
