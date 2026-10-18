package io.github.jer78.dockcontroller

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val TvArtworkDeleteColor = Color(0xFFFF5252)

// Settings > Home Assistant > Saved TV Artwork: every show logo, poster and
// YouTube thumbnail TV mode has found and saved. Deleting one has it looked
// up again the next time it plays - also the way to fix a wrong match.
@Composable
fun SavedTvArtworkDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { TvArtworkStore.getInstance(context) }
    val entries by store.entries.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var sizeOnDisk by remember { mutableLongStateOf(0L) }
    LaunchedEffect(entries) {
        sizeOnDisk = withContext(Dispatchers.IO) { store.sizeOnDisk() }
    }

    SettingsGlassDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.8f).widthIn(max = 720.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Saved TV Artwork", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmDeleteAll = true }, enabled = entries.isNotEmpty()) {
                    Text("Delete All", color = if (entries.isNotEmpty()) TvArtworkDeleteColor else Color.Gray)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Show logos, posters and YouTube thumbnails TV mode has found, saved on this device so each " +
                        "one is only looked up once. Delete one to have it looked up again the next time it plays - " +
                        "handy if it found the wrong show. Artwork not shown for 14 days is deleted by itself.",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Text(
                    "${entries.size} saved · ${Formatter.formatShortFileSize(context, sizeOnDisk)}",
                    fontWeight = FontWeight.SemiBold
                )
                HorizontalDivider()
                if (entries.isEmpty()) {
                    Text(
                        "Nothing saved yet. Artwork is saved here as TV mode finds it.",
                        fontSize = 14.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.5f).dp)
                    ) {
                        items(entries, key = { it.key }) { entry ->
                            SavedTvArtworkRow(
                                entry = entry,
                                imageUrl = store.imageUrl(entry.url),
                                onDelete = { scope.launch(Dispatchers.IO) { store.delete(entry.key) } }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )

    if (confirmDeleteAll) {
        DockAlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all saved TV artwork?") },
            text = { Text("Every show and video will be looked up again the next time it plays.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    scope.launch(Dispatchers.IO) { store.deleteAll() }
                }) {
                    Text("Delete All", color = TvArtworkDeleteColor, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SavedTvArtworkRow(entry: SavedTvArtwork, imageUrl: String, onDelete: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        // Posters keep their shape; logos sit whole on a dark tile, and
        // thumbnails fill theirs.
        val isPoster = entry.source == SavedTvArtwork.Source.TVMAZE_POSTER
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            contentScale = if (entry.source == SavedTvArtwork.Source.TMDB_LOGO) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier
                .height(56.dp)
                .aspectRatio(if (isPoster) entry.aspectRatio else 16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .padding(if (entry.source == SavedTvArtwork.Source.TMDB_LOGO) 4.dp else 0.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${entry.source.label} · Last shown ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(entry.lastUsedMs))}",
                fontSize = 12.sp,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete ${entry.name}", tint = TvArtworkDeleteColor)
        }
    }
}
