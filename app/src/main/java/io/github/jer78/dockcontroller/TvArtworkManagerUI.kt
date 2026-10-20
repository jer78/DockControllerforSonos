package io.github.jer78.dockcontroller

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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

// Settings > Home Assistant > Saved TV Cast: each show whose cast the TV
// screensaver has saved, as one entry - its regular cast and every episode's
// guest stars together. Deleting one has it looked up again next time it's on.
@Composable
fun SavedTvCastDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { TvShowCast.getInstance(context) }
    val shows by store.shows.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var sizeOnDisk by remember { mutableLongStateOf(0L) }
    LaunchedEffect(shows) {
        sizeOnDisk = withContext(Dispatchers.IO) { store.sizeOnDisk() }
    }

    SettingsGlassDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.8f).widthIn(max = 720.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Saved TV Cast", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmDeleteAll = true }, enabled = shows.isNotEmpty()) {
                    Text("Delete All", color = if (shows.isNotEmpty()) TvArtworkDeleteColor else Color.Gray)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The cast of each show the TV screensaver has shown, with their photos, saved on this device " +
                        "so each show is only looked up once (and again each week, for cast changes). Delete one to " +
                        "have it looked up again the next time it's on. A show not on for 14 days is deleted by itself.",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Text(
                    "${shows.size} show${if (shows.size == 1) "" else "s"} · ${Formatter.formatShortFileSize(context, sizeOnDisk)}",
                    fontWeight = FontWeight.SemiBold
                )
                HorizontalDivider()
                if (shows.isEmpty()) {
                    Text(
                        "Nothing saved yet. A show's cast is saved here once it's on in TV mode.",
                        fontSize = 14.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.5f).dp)
                    ) {
                        items(shows, key = { it.key }) { show ->
                            SavedTvCastRow(
                                show = show,
                                onDelete = { scope.launch(Dispatchers.IO) { store.deleteShow(show.key) } }
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
            title = { Text("Delete all saved TV cast?") },
            text = { Text("Every show's cast will be looked up again the next time it's on.") },
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

// A few of the show's people, overlapping, then its name and how many.
@Composable
private fun SavedTvCastRow(show: SavedShowCast, onDelete: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Box(Modifier.width(100.dp).height(56.dp)) {
            show.previewImageUrls.forEachIndexed { index, url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .offset(x = (index * 26).dp)
                        .size(48.dp)
                        .align(Alignment.CenterStart)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .border(2.dp, Color(0xFF2C2C30), CircleShape)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                show.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${show.people} ${if (show.people == 1) "person" else "people"} · Last shown " +
                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(show.lastUsedMs)),
                fontSize = 12.sp,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete ${show.name}'s cast", tint = TvArtworkDeleteColor)
        }
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
