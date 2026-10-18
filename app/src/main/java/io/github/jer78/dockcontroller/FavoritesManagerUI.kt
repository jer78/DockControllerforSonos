package io.github.jer78.dockcontroller

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

private val SonosBadgeColor = Color(0xFF4CAF50)
private val AppOnlyBadgeColor = Color(0xFFFFB300)
private val DeleteColor = Color(0xFFFF5252)

// Where a favorite lives: still in Sonos Favorites, or kept only by this app.
@Composable
fun FavoriteBadge(onSonos: Boolean, modifier: Modifier = Modifier) {
    val color = if (onSonos) SonosBadgeColor else AppOnlyBadgeColor
    Text(
        if (onSonos) "Sonos" else "App only",
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

private enum class FavoritesShown(val label: String) { ALL("All"), SONOS("On Sonos"), APP_ONLY("App Only") }

// What a confirm prompt is about to do to a favorite.
private enum class FavoriteAction { REMOVE_FROM_SONOS, DELETE_EVERYWHERE, DELETE_SAVED }

// Settings > Favorites: every favorite the app keeps, whether it's still in
// Sonos Favorites or not. Removing one from Sonos keeps it here, so Cover
// Flow can hold more than Sonos's ~70.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FavoritesManagerTab(
    favorites: List<AlbumHistoryItem>,
    onRemoveFromSonos: (AlbumHistoryItem) -> Unit,
    onDeleteEverywhere: (AlbumHistoryItem) -> Unit,
    onDeleteSaved: (AlbumHistoryItem) -> Unit
) {
    var shown by remember { mutableStateOf(FavoritesShown.ALL) }
    var pending by remember { mutableStateOf<Pair<AlbumHistoryItem, FavoriteAction>?>(null) }
    val onSonosCount = favorites.count { it.onSonos }
    val appOnlyCount = favorites.size - onSonosCount
    val list = when (shown) {
        FavoritesShown.ALL -> favorites
        FavoritesShown.SONOS -> favorites.filter { it.onSonos }
        FavoritesShown.APP_ONLY -> favorites.filter { !it.onSonos }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "The app keeps every favorite it finds on Sonos, even after it's removed there. " +
                "Remove favorites from Sonos to make room under its limit of about 70, and they'll " +
                "stay in Cover Flow here. A favorite that's no longer on Sonos might stop playing " +
                "if the music service changes it.",
            fontSize = 12.sp,
            color = Color.Gray
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$onSonosCount on Sonos · $appOnlyCount in app only",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FavoritesShown.entries.forEach { option ->
                    FilterChip(
                        selected = shown == option,
                        onClick = { shown = option },
                        label = { Text(option.label) }
                    )
                }
            }
        }
        HorizontalDivider()

        if (list.isEmpty()) {
            Text(
                if (favorites.isEmpty()) "No favorites yet. Refresh favorites on the Setup tab."
                else "Nothing to show here.",
                fontSize = 14.sp,
                color = Color.Gray,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }
        list.forEach { item ->
            FavoriteRow(
                item = item,
                onRemoveFromSonos = { pending = item to FavoriteAction.REMOVE_FROM_SONOS },
                onDelete = {
                    pending = item to if (item.onSonos) FavoriteAction.DELETE_EVERYWHERE else FavoriteAction.DELETE_SAVED
                }
            )
        }
    }

    pending?.let { (item, action) ->
        val (title, body, confirm) = when (action) {
            FavoriteAction.REMOVE_FROM_SONOS -> Triple(
                "Remove from Sonos Favorites?",
                "\"${item.albumTitle}\" will be removed from your Sonos Favorites, including in the Sonos app. " +
                    "The app keeps it, so it stays in Cover Flow.",
                "Remove from Sonos"
            )
            FavoriteAction.DELETE_EVERYWHERE -> Triple(
                "Delete everywhere?",
                "\"${item.albumTitle}\" will be permanently removed from your Sonos Favorites, including in the " +
                    "Sonos app, and from this app.",
                "Delete"
            )
            FavoriteAction.DELETE_SAVED -> Triple(
                "Delete from the app?",
                "\"${item.albumTitle}\" is no longer in your Sonos Favorites, so deleting it here removes it for good.",
                "Delete"
            )
        }
        DockAlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    when (action) {
                        FavoriteAction.REMOVE_FROM_SONOS -> onRemoveFromSonos(item)
                        FavoriteAction.DELETE_EVERYWHERE -> onDeleteEverywhere(item)
                        FavoriteAction.DELETE_SAVED -> onDeleteSaved(item)
                    }
                }) {
                    Text(confirm, color = DeleteColor, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun FavoriteRow(
    item: AlbumHistoryItem,
    onRemoveFromSonos: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        AsyncImage(
            model = item.artUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.08f))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.albumTitle,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(8.dp))
                FavoriteBadge(item.onSonos)
            }
            val kind = when (item.kind) {
                "playlist" -> "Playlist"
                "radio" -> "Radio"
                else -> "Album"
            }
            val artist = item.artist.takeUnless { it.isBlank() || it == "Unknown Artist" || item.kind == "radio" }
            Text(
                listOfNotNull(kind, artist).joinToString(" · "),
                fontSize = 12.sp,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (item.onSonos) {
            TextButton(onClick = onRemoveFromSonos) { Text("Remove from Sonos", fontSize = 13.sp) }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = if (item.onSonos) "Delete everywhere" else "Delete from the app",
                tint = DeleteColor
            )
        }
    }
}

// Asked when a favorite that's no longer on Sonos fails to play: its saved
// link may have gone stale (e.g. the album was pulled from the service).
@Composable
fun UnplayableFavoriteDialog(item: AlbumHistoryItem, onKeep: () -> Unit, onDelete: () -> Unit) {
    DockAlertDialog(
        onDismissRequest = onKeep,
        title = { Text("Favorite won't play") },
        text = {
            Text(
                "\"${item.albumTitle}\" is no longer in your Sonos Favorites, and its saved link didn't play. " +
                    "The music service may have removed it, or it may be a temporary problem. " +
                    "Delete it from the app, or keep it and try again later?"
            )
        },
        confirmButton = {
            TextButton(onClick = onDelete) {
                Text("Delete", color = DeleteColor, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onKeep) { Text("Keep") }
        }
    )
}
