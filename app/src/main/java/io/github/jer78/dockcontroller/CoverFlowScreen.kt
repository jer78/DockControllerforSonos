package io.github.jer78.dockcontroller

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.text.format.DateUtils
import androidx.compose.ui.unit.IntSize
import java.text.Normalizer
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// Gap under the Previous / Now Playing / Next Up cards - kept small so they
// sit low and leave the albums more room.
private val TrackCardBottomPadding = 8.dp
// Clear space kept between the grid and those cards.
private val TrackCardGridGap = 16.dp
// How long the Home Assistant widgets stay after the motion sensor clears,
// or after the last touch if nothing starts moving by itself before then.
private const val WIDGETS_LINGER_MS = 30_000L
// Room the pushed-right clock leaves for the controls button (24dp in from
// the edge, about 50dp wide).
private val ClockPushedEndPadding = 100.dp
// The least space kept between the widgets and the clock beside them.
private val ClockWidgetsGap = 32.dp

// One of Cover Flow's bottom track cards (Previous / Now Playing / Next Up).
@Composable
private fun CoverFlowTrackCard(
    label: String,
    title: String,
    subtitle: String,
    artUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color.Black.copy(alpha = 0.6f),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .width(360.dp)
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = label,
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (artUrl.isNotBlank()) {
                    AsyncImage(
                        model = artUrl,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                }
                Column {
                    Text(
                        text = title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

// Whose turn it is on the bottom-right track card.
private enum class TrackCardTurn { NOW_PLAYING, NEXT_UP, SUGGESTIONS }

// The track card for the last artist suggestions: their three covers, and a
// tap shows them again (see SuggestionsScreen).
@Composable
private fun CoverFlowSuggestionsCard(
    suggestions: List<SuggestedAlbum>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color.Black.copy(alpha = 0.6f),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .width(360.dp)
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "YOU MIGHT LIKE",
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                suggestions.take(3).forEach { suggestion ->
                    AsyncImage(
                        model = suggestion.artUrl,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = suggestions.joinToString(", ") { it.artist },
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// Matched by identity rather than index, since a refresh or a new sort
// reorders the list. playUri is the stable ID when present; items without
// one are identified by their art URL.
private fun coverFlowInitialPage(
    albums: List<AlbumHistoryItem>,
    pageCount: Int,
    centerAlbum: AlbumHistoryItem?
): Int {
    val center = pageCount / 2
    if (albums.isEmpty() || centerAlbum == null) return center
    val index = albums.indexOfFirst {
        if (centerAlbum.playUri.isNotBlank()) it.playUri == centerAlbum.playUri
        else it.artUrl == centerAlbum.artUrl
    }
    if (index < 0) return center
    return center - (center % albums.size) + index
}

private const val CONTROLS_AUTO_COLLAPSE_MS = 10_000L

private const val VARIOUS_ARTISTS = "Various Artists"

private const val REFRESH_RESULT_MS = 5_000L

// Items with no kind are albums.
private fun AlbumHistoryItem.isAlbum() = kind == "album" || kind.isEmpty()

// One of the round buttons in Cover Flow's top-right controls.
@Composable
private fun CoverFlowControlButton(
    icon: ImageVector,
    contentDescription: String,
    highlighted: Boolean = false,
    iconModifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = if (highlighted) Color.White.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.55f),
        shape = CircleShape,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick)
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (highlighted) Color.Black else Color.White.copy(alpha = 0.85f),
            modifier = Modifier.padding(12.dp).size(22.dp).then(iconModifier)
        )
    }
}

// Which favorites Cover Flow shows. Always starts on ALL - it isn't saved.
private enum class FavoriteCategory(val label: String, val kind: String?) {
    ALL("All Favorites", null),
    ALBUMS("Albums", "album"),
    PLAYLISTS("Playlists", "playlist"),
    RADIO("Radio", "radio");

    val icon: ImageVector
        get() = when (this) {
            ALL -> Icons.Default.Favorite
            ALBUMS -> Icons.Default.Album
            PLAYLISTS -> Icons.AutoMirrored.Filled.QueueMusic
            RADIO -> Icons.Default.Radio
        }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AmbientClockScreen(
    albumHistory: List<AlbumHistoryItem>,
    trackState: TrackState,
    onPlayAlbum: (AlbumHistoryItem, Rect) -> Unit,
    onWakeUp: () -> Unit,
    use24HourClock: Boolean = true,
    // 0..1 each, from Settings - see SettingsManager.COVER_FLOW_REFLECTION.
    reflectionStrength: Float = SettingsManager.COVER_FLOW_REFLECTION_DEFAULT,
    glassTableStrength: Float = SettingsManager.COVER_FLOW_GLASS_TABLE_DEFAULT,
    sideLightStrength: Float = SettingsManager.COVER_FLOW_SIDE_LIGHT_DEFAULT,
    // The X (and the grid's long press) permanently deletes the favorite,
    // from Sonos too if it's still there, after a confirmation prompt.
    onDeleteFavorite: (AlbumHistoryItem) -> Unit = {},
    // Album to start centered on, and a callback whenever a new one settles
    // in the center - lets the caller resume the rotation where it left off.
    initialCenterAlbum: AlbumHistoryItem? = null,
    onCenterAlbumChanged: (AlbumHistoryItem) -> Unit = {},
    // The same for the grid: where it was scrolled to, and a callback as
    // it moves.
    initialGridPosition: GridScrollPosition? = null,
    onGridPositionChanged: (GridScrollPosition) -> Unit = {},
    // Settings > Display > Cover Flow Track Cards.
    hidePrevious: Boolean = false,
    hideNextUp: Boolean = false,
    hideNowPlaying: Boolean = false,
    // The first Home Assistant weather widget's entity, shown beside the
    // clock's time when there is one.
    weather: HaWeatherState? = null,
    // The saved Cover Flow / grid choice, and where to save a new one.
    gridView: Boolean = false,
    onGridViewChange: (Boolean) -> Unit = {},
    // The saved album order - by artist, or by album title - and where to
    // save a new one. Playlists and radio always go by title.
    sortByArtist: Boolean = true,
    onSortChange: (Boolean) -> Unit = {},
    // Favorites mode's refresh button, spinning while a refresh runs, and
    // then saying what it found.
    onRefreshFavorites: ((FavoritesRefreshResult) -> Unit) -> Unit = {},
    isRefreshingFavorites: Boolean = false,
    // Extra room on the left of the bottom-left Previous card, and above the
    // top-right controls, for overlays the system draws there (the Pixel
    // Tablet's Home button and status line over the screensaver).
    bottomStartInset: Dp = 0.dp,
    topEndInset: Dp = 0.dp,
    // Now Playing's Home Assistant widgets, slid in beside the clock as set
    // in Settings > Now Playing (see CoverFlowWidgetsMode).
    haState: HAState = HAState(),
    widgetsMode: CoverFlowWidgetsMode = CoverFlowWidgetsMode.OFF,
    onHaCommand: (HaCommand) -> Unit = {},
    onHaCommandWithResult: (HaCommand, (HaServiceResult) -> Unit) -> Unit = { _, _ -> },
    onReorderHaWidgets: (List<HaWidgetSlot>) -> Unit = {},
    // The last few favorites played (favoriteKey -> when), which the
    // drifting grid brings to the front in turn when showRecentlyPlayed.
    recentlyPlayed: List<Pair<String, Long>> = emptyList(),
    showRecentlyPlayed: Boolean = false,
    // Start cycling (Cover Flow) or drifting (the grid) straight away,
    // rather than after being left alone for a while - for Settings > Now
    // Playing > Show Cover Flow Between Songs. A touch first cancels it.
    startScrolling: Boolean = false,
    // The artist suggestions last shown for the playing artist, offered
    // again among the track cards, and showing them.
    suggestions: List<SuggestedAlbum> = emptyList(),
    onShowSuggestions: () -> Unit = {},
    // Replay in the Previous track's details (see SonosDashboardManager.replayTrack).
    canReplayTrack: suspend (TrackDetails) -> Boolean = { false },
    onReplayTrack: (TrackDetails) -> Unit = {}
) {
    var isFadingOut by remember { mutableStateOf(false) }
    var scrollAtOnce by remember { mutableStateOf(startScrolling) }
    // Favorite awaiting the user's confirm/cancel, and then the one they
    // confirmed (which drives that card's drop animation before deleting).
    var pendingFavoriteDelete by remember { mutableStateOf<AlbumHistoryItem?>(null) }
    var confirmedFavoriteDelete by remember { mutableStateOf<AlbumHistoryItem?>(null) }

    // The top-right controls: which kind of favorite to show, and Cover Flow
    // or a side-scrolling grid. The category resets each time Cover Flow
    // opens; the view is whichever the user last picked (search's own trip
    // to the grid doesn't count).
    var category by remember { mutableStateOf(FavoriteCategory.ALL) }
    var isGridView by remember(gridView) { mutableStateOf(gridView) }
    // Only kinds that actually have favorites are offered.
    val categories = remember(albumHistory) {
        listOf(FavoriteCategory.ALL) + FavoriteCategory.entries.filter { c -> c.kind != null && albumHistory.any { it.kind == c.kind } }
    }
    if (category !in categories) category = FavoriteCategory.ALL
    // Genres known for the favorites (Apple Music albums only, from Apple's
    // catalog), A-Z. null = all genres. Like the category, not saved.
    var genre by remember { mutableStateOf<String?>(null) }
    val genres = remember(albumHistory) {
        albumHistory.map { it.genre }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
    }
    if (genre != null && genre !in genres) genre = null
    var isSortByArtist by remember(sortByArtist) { mutableStateOf(sortByArtist) }
    // What each item is sorted (and the grid's A-Z bar lettered) by. By
    // artist, playlists count as Various Artists, so they gather under V.
    fun sortKey(item: AlbumHistoryItem): String = when {
        !isSortByArtist -> item.albumTitle
        item.isAlbum() -> item.artist
        item.kind == "playlist" -> VARIOUS_ARTISTS
        else -> item.albumTitle
    }
    // Sorted by artist, albums show their artist first, in bold.
    fun artistFirst(item: AlbumHistoryItem) = isSortByArtist && item.isAlbum() && item.artist.isNotBlank()
    val shownAlbums = remember(albumHistory, category, genre, isSortByArtist) {
        val inCategory = category.kind?.let { kind -> albumHistory.filter { it.kind == kind } } ?: albumHistory
        val inGenre = genre?.let { g -> inCategory.filter { it.genre == g } } ?: inCategory
        inGenre.sortedWith(
            compareBy<AlbumHistoryItem>({ sortKey(it).lowercase() }, { it.albumTitle.lowercase() }, { it.artist.lowercase() })
        )
    }
    val showsAlbums = shownAlbums.any { it.isAlbum() }

    // The top-right controls fold away behind one button, and fold back up
    // after CONTROLS_AUTO_COLLAPSE_MS without a tap (kept open while
    // searching, whose button closes the search).
    var controlsOpen by remember { mutableStateOf(false) }

    // The Home Assistant widgets. In NEAR mode they come in on a touch, and
    // go again as soon as Cover Flow or the grid starts moving by itself
    // again (bumped in autoScrolls). The motion sensor keeps them in while
    // it detects someone, and for WIDGETS_LINGER_MS after it clears - so a
    // sensor that briefly clears while someone stands still doesn't snap
    // them away. An open widget pop-up keeps them too. They make way for
    // the controls.
    var openHaEntity by remember { mutableStateOf<String?>(null) }
    var screenTouches by remember { mutableIntStateOf(0) }
    var autoScrolls by remember { mutableIntStateOf(0) }
    var touched by remember { mutableStateOf(false) }
    // Short grids never drift, so a touch also wears off by itself.
    LaunchedEffect(screenTouches) {
        if (screenTouches > 0) {
            touched = true
            delay(WIDGETS_LINGER_MS)
            touched = false
        }
    }
    LaunchedEffect(autoScrolls) {
        touched = false
    }
    var motionNear by remember { mutableStateOf(haState.presenceDetected) }
    LaunchedEffect(haState.presenceDetected) {
        if (haState.presenceDetected) motionNear = true else {
            delay(WIDGETS_LINGER_MS)
            motionNear = false
        }
    }
    val nearby = touched || motionNear
    val hasHaWidgets = haState.widgets.any { slot -> haState.entities[slot.entityId].let { it != null && it !is HaSunState } }
    val showHaWidgets = hasHaWidgets && !controlsOpen && when (widgetsMode) {
        CoverFlowWidgetsMode.ALWAYS -> true
        CoverFlowWidgetsMode.NEAR -> nearby || openHaEntity != null
        CoverFlowWidgetsMode.OFF -> false
    }

    // The bottom track cards' measured height, so the grid stops above them
    // whatever the font size. Until they've been drawn, a close estimate.
    val screenDensity = LocalDensity.current
    var trackCardHeight by remember { mutableStateOf(126.dp) }
    fun onTrackCardMeasured(heightPx: Int) {
        val height = with(screenDensity) { heightPx.toDp() }
        if (height > trackCardHeight) trackCardHeight = height
    }
    var controlsTaps by remember { mutableIntStateOf(0) }
    // What the last refresh-button tap found, shown under the controls for
    // REFRESH_RESULT_MS.
    var refreshResult by remember { mutableStateOf<FavoritesRefreshResult?>(null) }
    LaunchedEffect(refreshResult) {
        if (refreshResult != null) {
            delay(REFRESH_RESULT_MS)
            refreshResult = null
        }
    }

    // Search, typed on Cover Flow's own keyboard. It narrows the grid only -
    // opening it switches to the grid, and closing it clears the search and
    // goes back to whichever view was showing.
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var gridBeforeSearch by remember { mutableStateOf(false) }
    val gridAlbums = remember(shownAlbums, query) { filterBySearch(shownAlbums, query) }
    fun closeSearch() {
        searchOpen = false
        query = ""
        isGridView = gridBeforeSearch
    }
    LaunchedEffect(controlsOpen, controlsTaps, searchOpen) {
        if (controlsOpen && !searchOpen) {
            delay(CONTROLS_AUTO_COLLAPSE_MS)
            controlsOpen = false
        }
    }

    // Fallback if no centered card picked up the confirmed delete (e.g. the
    // pager moved) - delete without the animation rather than leave it
    // pending, where it would fire whenever that card next scrolled in.
    LaunchedEffect(confirmedFavoriteDelete) {
        val item = confirmedFavoriteDelete ?: return@LaunchedEffect
        delay(1500)
        if (confirmedFavoriteDelete == item) {
            confirmedFavoriteDelete = null
            onDeleteFavorite(item)
        }
    }
    val fadeAlpha by animateFloatAsState(
        targetValue = if (isFadingOut) 0f else 1f,
        animationSpec = tween(600),
        label = "fadeAlpha"
    )

    var currentTime by remember { mutableStateOf(Calendar.getInstance()) }
    var clockAlignment by remember { mutableStateOf(Alignment.TopCenter) }
    var showDebugInfo by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = Calendar.getInstance()
            delay(1000)
        }
    }
    
    LaunchedEffect(Unit) {
        val alignments = listOf(Alignment.TopStart, Alignment.TopCenter)
        var currentIndex = 1 
        while (true) {
            delay(120_000) 
            currentIndex = (currentIndex + 1) % alignments.size
            clockAlignment = alignments[currentIndex]
        }
    }

    // A finite virtual page count (previously 1000) runs out after enough
    // auto-advances (~2h13m at 8s/page) - animateScrollToPage() can't go
    // past the last page, so the cover flow silently stops wrapping and
    // sits on one album until the dream restarts. Int.MAX_VALUE looks like
    // the obvious fix but Compose's Pager breaks internally at that count
    // (animateScrollToPage stops moving, and it can crash) - so instead use
    // a large-but-safe count and silently recenter (see the auto-advance
    // effect below) well before it's ever exhausted.
    val internalPageCount = if (shownAlbums.isEmpty()) 0 else 100_000
    val pagerState = rememberPagerState(
        initialPage = coverFlowInitialPage(shownAlbums, internalPageCount, initialCenterAlbum),
        pageCount = { internalPageCount }
    )

    LaunchedEffect(pagerState, shownAlbums) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (shownAlbums.isNotEmpty()) onCenterAlbumChanged(shownAlbums[page % shownAlbums.size])
        }
    }

    // A different category is a different list - land on the same album if
    // it's in there, else start from the middle.
    var lastCategory by remember { mutableStateOf(category) }
    LaunchedEffect(category) {
        if (category == lastCategory) return@LaunchedEffect
        lastCategory = category
        if (shownAlbums.isNotEmpty()) {
            pagerState.scrollToPage(coverFlowInitialPage(shownAlbums, internalPageCount, initialCenterAlbum))
        }
    }

    val isDragged by pagerState.interactionSource.collectIsDraggedAsState()
    var isUserInteracting by remember { mutableStateOf(false) }
    var showLabels by remember { mutableStateOf(false) }
    var previousTrackDetails by remember { mutableStateOf<TrackDetails?>(null) }

    // The grid's A-Z bar, along the bottom of Cover Flow while it's being
    // swiped through (the track cards step aside for it). It goes when the
    // centered album's play and X buttons do (showLabels). Bumped on each
    // letter picked, which also holds off the auto-advance.
    var letterPicks by remember { mutableIntStateOf(0) }
    var showCoverFlowLetterBar by remember { mutableStateOf(false) }
    val coverFlowLetters = remember(shownAlbums, isSortByArtist) { shownAlbums.map { indexLetter(sortKey(it)) } }
    val hasCoverFlowLetterBar = !isGridView && coverFlowLetters.size > 24
    LaunchedEffect(isDragged, letterPicks) {
        if (isDragged || letterPicks > 0) showCoverFlowLetterBar = true
    }
    LaunchedEffect(showLabels) {
        if (!showLabels) showCoverFlowLetterBar = false
    }
    val letterBarShown = hasCoverFlowLetterBar && showCoverFlowLetterBar

    LaunchedEffect(isUserInteracting, isDragged, letterPicks) {
        if (isUserInteracting || isDragged) {
            showLabels = true
        } else {
            delay(5000) 
            showLabels = false
        }
    }

    LaunchedEffect(shownAlbums, isUserInteracting, isDragged, pendingFavoriteDelete, isGridView, letterPicks) {
        if (isUserInteracting || isDragged || letterPicks > 0) scrollAtOnce = false
        if (shownAlbums.isNotEmpty() && pendingFavoriteDelete == null && !isGridView) {
            while (true) {
                // Just long enough to slide in first.
                delay(if (scrollAtOnce) START_SCROLLING_DELAY_MS else 8000)
                scrollAtOnce = false
                if (pagerState.pageCount > 0 && !isUserInteracting && !isDragged && !pagerState.isScrollInProgress) {
                    autoScrolls++
                    pagerState.animateScrollToPage(
                        pagerState.currentPage + 1,
                        animationSpec = tween(1500, easing = FastOutSlowInEasing)
                    )
                    // The virtual page range is large but still finite - if a
                    // dream session runs long enough to approach either edge,
                    // silently snap (no animation, same album via modulo) back
                    // near the middle so it can keep advancing indefinitely.
                    val page = pagerState.currentPage
                    val count = pagerState.pageCount
                    if (page < 1000 || page > count - 1000) {
                        val center = count / 2
                        val recentered = center - (center % shownAlbums.size) + (page % shownAlbums.size)
                        pagerState.scrollToPage(recentered)
                    }
                }
            }
        }
    }

    val screenWidth = LocalConfiguration.current.screenWidthDp
    val hPadding = ((screenWidth - 450) / 2).coerceAtLeast(0).dp

    // Window bounds of every album card currently laid out (keyed by pager
    // page), so a tap on or near artwork never wakes to Now Playing - even
    // if it lands in the gap around the X, or just as the play/X controls
    // time out and disappear (they're the only things that would otherwise
    // consume it). Read only from the tap handler, so a plain map is enough.
    val cardBoundsByPage = remember { HashMap<Int, Rect>() }
    var screenOrigin by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onGloballyPositioned { screenOrigin = it.boundsInWindow().topLeft }
            .debugToggleHoldGesture { showDebugInfo = !showDebugInfo }
            // Any touch - swiping the albums, scrolling the grid - counts as
            // someone being near, for the widgets. Only watched, never taken.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    screenTouches++
                }
            }
            .pointerInput(Unit) {
                val artworkMarginPx = 32.dp.toPx()
                detectTapGestures(onTap = { offset ->
                    val tap = screenOrigin + offset
                    val onArtwork = cardBoundsByPage.values.any { it.inflate(artworkMarginPx).contains(tap) }
                    if (!onArtwork) onWakeUp()
                })
            }
    ) {
        // The "glass table" the cards stand on. At strength 0 it's just the
        // original faint gradient; turning it up brightens the surface and
        // adds a lit front edge plus a sheen just below it, like the edge of
        // a glass shelf catching the light.
        val glass = glassTableStrength.coerceIn(0f, 1f)
        val sideLight = sideLightStrength.coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.35f)
                .alpha(fadeAlpha)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.White.copy(alpha = lerp(0.05f, 0.18f, glass)))
                    )
                )
                .drawWithContent {
                    drawContent()
                    if (glass > 0f) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.White.copy(alpha = 0.10f * glass), Color.Transparent),
                                endY = size.height * 0.25f
                            )
                        )
                        drawLine(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.45f * glass),
                                    Color.Transparent
                                )
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(size.width, 0f),
                            strokeWidth = 1.5.dp.toPx()
                        )
                    }
                    if (sideLight > 0f) {
                        // Light coming in from the left: a glow pooled where
                        // it enters the glass near the front edge, a beam
                        // that skims across the surface and fades out toward
                        // the right, and a faint spectral fringe along the
                        // beam where the glass edge splits the light.
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(Color.White.copy(alpha = 0.22f * sideLight), Color.Transparent),
                                center = Offset(0f, size.height * 0.12f),
                                radius = size.width * 0.45f
                            )
                        )
                        // The beam is a round glow stretched sideways into a
                        // long ellipse, so it fades out softly on every side
                        // instead of ending in a hard-edged band.
                        val beamCenter = Offset(0f, size.height * 0.10f)
                        val beamRadius = size.height * 0.30f
                        scale(scaleX = size.width * 0.9f / beamRadius, scaleY = 1f, pivot = beamCenter) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    0f to Color.White.copy(alpha = 0.16f * sideLight),
                                    0.5f to Color.White.copy(alpha = 0.05f * sideLight),
                                    1f to Color.Transparent,
                                    center = beamCenter,
                                    radius = beamRadius
                                ),
                                radius = beamRadius,
                                center = beamCenter
                            )
                        }
                        val fringeTop = size.height * 0.04f
                        drawRect(
                            brush = Brush.horizontalGradient(
                                0f to Color(0xFF7FDBFF).copy(alpha = 0.10f * sideLight),
                                0.18f to Color(0xFFB388FF).copy(alpha = 0.07f * sideLight),
                                0.36f to Color(0xFFFF80AB).copy(alpha = 0.04f * sideLight),
                                0.55f to Color.Transparent
                            ),
                            topLeft = Offset(0f, fringeTop),
                            size = Size(size.width, 2.dp.toPx())
                        )
                    }
                }
        )

        if (shownAlbums.isNotEmpty() && isGridView) {
            CoverGrid(
                albums = gridAlbums,
                // Leaves room for the search keyboard, or the track cards
                // while they're showing.
                bottomPadding = when {
                    searchOpen -> SearchPanelHeight + 16.dp
                    trackState.isPlaying && (!(hidePrevious && hideNowPlaying && hideNextUp) || suggestions.isNotEmpty()) ->
                        trackCardHeight + TrackCardBottomPadding + TrackCardGridGap
                    else -> 32.dp
                },
                letterOf = { sortKey(it) },
                // Search results are a list of their own, not resumed.
                initialPosition = initialGridPosition.takeUnless { searchOpen },
                onPositionChanged = { if (!searchOpen) onGridPositionChanged(it) },
                artistFirst = { artistFirst(it) },
                showLetterBar = !searchOpen,
                emptyText = "No matches for \"${query.trim()}\"",
                onPlay = { album, bounds ->
                    isFadingOut = true
                    onPlayAlbum(album, bounds)
                },
                onLongPress = { pendingFavoriteDelete = it },
                // The same rules as Cover Flow's own auto-advance.
                autoScroll = !searchOpen && pendingFavoriteDelete == null,
                driftAtOnce = scrollAtOnce,
                onDriftStart = {
                    autoScrolls++
                    scrollAtOnce = false
                },
                recentlyPlayed = remember(recentlyPlayed, showRecentlyPlayed) {
                    if (showRecentlyPlayed) recentlyPlayed.toMap() else emptyMap()
                },
                // The newest one played is the one playing, while its album is.
                nowPlayingKey = recentlyPlayed.firstOrNull()?.first?.takeIf { key ->
                    trackState.isPlaying && albumHistory.any {
                        favoriteKey(it) == key && it.albumTitle.isNotBlank() && trackState.album.isNotBlank() &&
                            (it.albumTitle.contains(trackState.album, ignoreCase = true) ||
                                trackState.album.contains(it.albumTitle, ignoreCase = true))
                    }
                },
                modifier = Modifier.fillMaxSize().alpha(fadeAlpha)
            )
        } else if (shownAlbums.isNotEmpty()) {
            Box(Modifier.fillMaxSize().alpha(fadeAlpha), contentAlignment = Alignment.Center) {
                HorizontalPager(
                    state = pagerState,
                    contentPadding = PaddingValues(horizontal = hPadding),
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(y = 30.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) { page ->
                    val actualPage = page % shownAlbums.size
                    val albumItem = shownAlbums[actualPage]
                    val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                    
                    val scale = lerp(start = 0.6f, stop = 1f, fraction = 1f - abs(pageOffset).coerceIn(0f, 1f))
                    val rotationY = lerp(start = if (pageOffset < 0) -50f else 50f, stop = 0f, fraction = 1f - abs(pageOffset).coerceIn(0f, 1f))

                    val dropAnim = remember { Animatable(0f) }
                    val isBeingRemoved = remember { mutableStateOf(false) }

                    // Only the centered copy animates - with a short list the
                    // same favorite also appears on other (virtual) pages.
                    LaunchedEffect(confirmedFavoriteDelete) {
                        if (confirmedFavoriteDelete == albumItem && page == pagerState.currentPage) {
                            isBeingRemoved.value = true
                        }
                    }

                    LaunchedEffect(isBeingRemoved.value) {
                        if (isBeingRemoved.value) {
                            dropAnim.animateTo(
                                targetValue = -60f,
                                animationSpec = tween(150, easing = LinearOutSlowInEasing)
                            )
                            dropAnim.animateTo(
                                targetValue = 2000f,
                                animationSpec = tween(400, easing = LinearEasing)
                            )
                            if (confirmedFavoriteDelete == albumItem) {
                                confirmedFavoriteDelete = null
                                onDeleteFavorite(albumItem)
                            }
                            isBeingRemoved.value = false
                            dropAnim.snapTo(0f)
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(450.dp) 
                            .fillMaxHeight()
                            .offset { IntOffset(0, dropAnim.value.roundToInt()) }
                            .pointerInput(actualPage) {
                                awaitPointerEventScope {
                                    awaitFirstDown(requireUnconsumed = false)
                                    isUserInteracting = true
                                    var isVerticalDrag = false
                                    var dragTotalY = 0f
                                    var dragTotalX = 0f
                                    do {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.first()
                                        val dragAmount = change.position - change.previousPosition
                                        dragTotalX += abs(dragAmount.x)
                                        dragTotalY += abs(dragAmount.y)
                                        if (!isVerticalDrag && dragTotalY > 15f && dragTotalY > dragTotalX) isVerticalDrag = true
                                        if (isVerticalDrag && abs(pageOffset) < 0.2f) {
                                            change.consume()
                                        }
                                    } while (event.changes.any { it.pressed })
                                    isUserInteracting = false
                                }
                            },
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(450.dp)
                                .graphicsLayer {
                                    this.scaleX = scale
                                    this.scaleY = scale
                                    this.rotationY = rotationY
                                    this.cameraDistance = 15f * density
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            // Stronger = brighter and sharper (less blur), and
                            // fading out toward its far edge like a real
                            // reflection on glass. At 0 it's the original
                            // faint, evenly-blurred one.
                            val reflection = reflectionStrength.coerceIn(0f, 1f)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .offset(y = 460.dp)
                                    .graphicsLayer {
                                        rotationX = 180f
                                        alpha = lerp(0.12f, 0.5f, reflection) * (1f - (dropAnim.value / 1000f).coerceIn(0f, 1f))
                                        scaleY = 0.7f
                                        compositingStrategy = CompositingStrategy.Offscreen
                                    }
                                    .drawWithContent {
                                        drawContent()
                                        // Flipped by rotationX, so the local
                                        // top is the reflection's far end.
                                        drawRect(
                                            brush = Brush.verticalGradient(
                                                colors = listOf(Color.White.copy(alpha = 1f - reflection), Color.White)
                                            ),
                                            blendMode = BlendMode.DstIn
                                        )
                                    }
                                    .blur(lerp(8f, 1.5f, reflection).dp)
                            ) {
                                AsyncImage(
                                    model = albumItem.artUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            var cardBounds by remember { mutableStateOf(Rect.Zero) }
                            DisposableEffect(page) {
                                onDispose { cardBoundsByPage.remove(page) }
                            }
                            val isPlayable = abs(pageOffset) < 0.2f && showLabels && !isFadingOut
                            // Square corners, matching the square-edged
                            // reflection on the glass table below.
                            Card(
                                shape = RectangleShape,
                                elevation = CardDefaults.cardElevation(24.dp),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .border(1.dp, Color.White.copy(alpha = 0.1f), RectangleShape)
                                    .onGloballyPositioned {
                                        cardBounds = it.boundsInWindow()
                                        cardBoundsByPage[page] = cardBounds
                                    }
                                    .then(
                                        // The whole card is tappable-to-play
                                        // whenever the play button is showing
                                        // (not just its small hit target) -
                                        // Favorite/Remove buttons below still
                                        // win at their own exact positions,
                                        // since they're nested clickables
                                        // that consume the tap first.
                                        if (isPlayable) {
                                            Modifier.clickable {
                                                isFadingOut = true
                                                onPlayAlbum(albumItem, cardBounds)
                                            }
                                        } else Modifier
                                    )
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    AsyncImage(
                                        model = albumItem.artUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )

                                    if (isPlayable) {
                                        Surface(
                                            color = Color.Black.copy(alpha = 0.7f),
                                            shape = CircleShape,
                                            modifier = Modifier.size(110.dp)
                                                .clickable {
                                                    isFadingOut = true
                                                    onPlayAlbum(albumItem, cardBounds)
                                                }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Play",
                                                tint = Color.White,
                                                modifier = Modifier.padding(24.dp).fillMaxSize()
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                pendingFavoriteDelete = albumItem
                                            },
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(16.dp)
                                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Remove",
                                                tint = Color.White
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(45.dp))
                        
                        AnimatedVisibility(
                            visible = showLabels && abs(pageOffset) < 0.1f,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.width(450.dp)
                            ) {
                                val byArtist = artistFirst(albumItem)
                                Text(
                                    text = if (byArtist) albumItem.artist else albumItem.albumTitle,
                                    color = Color.White,
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (byArtist) albumItem.albumTitle else albumItem.artist,
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 20.sp,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxSize().alpha(fadeAlpha), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val painter = painterResource(id = R.drawable.ic_no_history)
                    Image(
                        painter = painter,
                        contentDescription = null,
                        modifier = Modifier.size(220.dp).alpha(0.3f)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        "No Albums Found",
                        color = Color.Gray,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Light
                    )
                    Text(
                        "Please go to Settings and Refresh Favorites",
                        color = Color.Gray.copy(alpha = 0.5f),
                        fontSize = 18.sp
                    )
                }
            }
        }

        // Track cards along the bottom while music plays: Previous on the
        // left, and on the right Now Playing, which every so often slides
        // off to the right to make way for Next Up, then slides back. Each
        // can be hidden in Settings; with only one of the right-hand pair
        // left (or no next track known), that one just stays put.
        val hasPrevious = trackState.prevTrack != "None"
        val hasNext = trackState.nextTrack != "None"
        // The search keyboard takes the bottom of the screen while it's open.
        val showPreviousCard = trackState.isPlaying && hasPrevious && !hidePrevious && !searchOpen
        val showNowPlayingCard = trackState.isPlaying && !hideNowPlaying && !searchOpen
        val showNextUpCard = trackState.isPlaying && hasNext && !hideNextUp && !searchOpen
        // While the A-Z bar is up, the cards drop out of the way below the
        // screen's edge as it rises, and come back up as it goes.
        val cardsAway by animateFloatAsState(
            targetValue = if (letterBarShown) 1f else 0f,
            animationSpec = tween(if (letterBarShown) 300 else 250, easing = FastOutSlowInEasing),
            label = "trackCardsAway"
        )
        val cardsAwayModifier = Modifier.graphicsLayer {
            translationY = cardsAway * (size.height + TrackCardBottomPadding.toPx())
            alpha = 1f - cardsAway
        }

        AnimatedVisibility(
            visible = showPreviousCard,
            enter = fadeIn(tween(600)),
            exit = fadeOut(tween(600)),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 24.dp + bottomStartInset, top = 24.dp, end = 24.dp, bottom = TrackCardBottomPadding)
                .onSizeChanged { onTrackCardMeasured(it.height) }
                .alpha(fadeAlpha)
                .then(cardsAwayModifier)
        ) {
            CoverFlowTrackCard(
                label = "PREVIOUS",
                title = trackState.prevTrack,
                subtitle = trackState.prevArtist,
                artUrl = trackState.prevArtUrl,
                onClick = { previousTrackDetails = trackState.previousTrackDetails() }
            )
        }

        // The last artist suggestions take a turn after Next Up.
        val showSuggestionsCard = trackState.isPlaying && suggestions.isNotEmpty() && !searchOpen
        if (showNowPlayingCard || showNextUpCard || showSuggestionsCard) {
            val turns = listOfNotNull(
                TrackCardTurn.NOW_PLAYING.takeIf { showNowPlayingCard },
                TrackCardTurn.NEXT_UP.takeIf { showNextUpCard },
                TrackCardTurn.SUGGESTIONS.takeIf { showSuggestionsCard }
            )
            var turn by remember { mutableStateOf(turns.first()) }
            // Restarts on every track change, so a new song always opens on
            // its own Now Playing card first.
            LaunchedEffect(turns, trackState.title) {
                turn = turns.first()
                if (turns.size > 1) {
                    var i = 0
                    while (true) {
                        delay(if (turns[i] == TrackCardTurn.NOW_PLAYING) 12_000 else 6_000)
                        i = (i + 1) % turns.size
                        turn = turns[i]
                    }
                }
            }
            AnimatedContent(
                targetState = if (turn in turns) turn else turns.first(),
                transitionSpec = {
                    (slideInHorizontally(tween(700, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(700))) togetherWith
                        (slideOutHorizontally(tween(700, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(500)))
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = TrackCardBottomPadding)
                    .onSizeChanged { onTrackCardMeasured(it.height) }
                    .alpha(fadeAlpha)
                    .then(cardsAwayModifier),
                label = "nowPlayingNextUp"
            ) { shown ->
                when (shown) {
                    TrackCardTurn.NEXT_UP -> CoverFlowTrackCard(
                        label = "NEXT UP",
                        title = trackState.nextTrack,
                        subtitle = trackState.nextArtist,
                        artUrl = trackState.nextArtUrl,
                        onClick = onWakeUp
                    )
                    TrackCardTurn.NOW_PLAYING -> CoverFlowTrackCard(
                        label = "NOW PLAYING",
                        title = trackState.title,
                        subtitle = if (trackState.album.isNotBlank()) "${trackState.artist} • ${trackState.album}" else trackState.artist,
                        artUrl = trackState.albumArtUrl,
                        onClick = onWakeUp
                    )
                    TrackCardTurn.SUGGESTIONS -> CoverFlowSuggestionsCard(
                        suggestions = suggestions,
                        onClick = onShowSuggestions
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = letterBarShown,
            enter = slideInVertically(tween(300, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(300)),
            exit = slideOutVertically(tween(250, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(250)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 48.dp + bottomStartInset, end = 48.dp, bottom = TrackCardBottomPadding + 16.dp)
                .alpha(fadeAlpha)
        ) {
            val centered by remember(coverFlowLetters) {
                derivedStateOf { coverFlowLetters.getOrNull(pagerState.currentPage % coverFlowLetters.size.coerceAtLeast(1)) }
            }
            LetterBar(
                present = remember(coverFlowLetters) { coverFlowLetters.toSet() },
                current = centered,
                onLetter = { letter ->
                    letterPicks++
                    showLabels = true
                    // The first album at that letter, or the next one there
                    // is - the copy of it nearest the current page.
                    val target = INDEX_LETTERS.indexOf(letter)
                    val index = coverFlowLetters.indexOfFirst { INDEX_LETTERS.indexOf(it) >= target }
                        .takeIf { it >= 0 } ?: coverFlowLetters.lastIndex
                    val page = pagerState.currentPage
                    scope.launch { pagerState.scrollToPage(page - page % coverFlowLetters.size + index) }
                }
            )
        }

        // The open controls stretch across the top right, so a centered
        // time slides over to the left as they open - in step with them, as
        // if they push it - and back as they close. The Home Assistant
        // widgets push it the other way, to the right, just the same. (Its
        // periodic move between the left and the center still fades.)
        val controlsPush by animateFloatAsState(
            targetValue = if (controlsOpen) 1f else 0f,
            animationSpec = tween(if (controlsOpen) 300 else 250, easing = FastOutSlowInEasing),
            label = "clockPush"
        )
        val widgetsPush by animateFloatAsState(
            targetValue = if (showHaWidgets) 1f else 0f,
            animationSpec = tween(if (showHaWidgets) 300 else 250, easing = FastOutSlowInEasing),
            label = "clockWidgetsPush"
        )
        // The time and date's widths (not the weather's, which folds away
        // for the widgets), and the widgets' - when they can't all fit
        // across the top, the clock fades out while the widgets are in,
        // giving them the whole width.
        var clockTimeWidth by remember { mutableIntStateOf(0) }
        var clockDateWidth by remember { mutableIntStateOf(0) }
        var haWidgetsWidth by remember { mutableIntStateOf(0) }
        val topWidth = screenWidth.dp - 24.dp - ClockPushedEndPadding
        val clockFitsBesideWidgets = with(screenDensity) {
            (haWidgetsWidth + maxOf(clockTimeWidth, clockDateWidth)).toDp() + ClockWidgetsGap <= topWidth
        }
        val clockAlpha by animateFloatAsState(
            targetValue = if (showHaWidgets && !clockFitsBesideWidgets) 0f else 1f,
            animationSpec = tween(if (showHaWidgets) 300 else 250, easing = FastOutSlowInEasing),
            label = "clockMakeRoom"
        )
        // Pushed right, the time stops short of the controls button.
        val clockEnd = lerp(60f, ClockPushedEndPadding.value, widgetsPush).dp
        AnimatedContent(
            targetState = clockAlignment,
            transitionSpec = { fadeIn(tween(1500)) togetherWith fadeOut(tween(1500)) },
            modifier = Modifier.fillMaxSize().padding(top = 20.dp, start = 60.dp, end = clockEnd).alpha(fadeAlpha * clockAlpha),
            label = "clockMove"
        ) { alignment ->
            val restBias = if (alignment == Alignment.TopCenter) 0f else -1f
            val pushedRight = lerp(restBias, 1f, widgetsPush)
            val boxAlignment = BiasAlignment(lerp(pushedRight, -1f, controlsPush), -1f)
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = boxAlignment) {
                Column(
                    horizontalAlignment = when {
                        alignment.toString().contains("End") -> Alignment.End
                        alignment.toString().contains("Start") -> Alignment.Start
                        else -> Alignment.CenterHorizontally
                    }
                ) {
                    // The weather sits beside the time rather than under the
                    // date, so it stays clear of the album art and the grid's
                    // A-Z bar: on the outer side when the clock is on the
                    // left, otherwise on the time's left.
                    // While the widgets are in, the weather widget among them
                    // already shows it, so it folds away to leave them room.
                    val clockWeather = weather?.takeIf { it.temperature != null }
                    val weatherAfter = alignment.toString().contains("Start")
                    val weatherEnter = expandHorizontally(tween(300)) + fadeIn(tween(300))
                    val weatherExit = shrinkHorizontally(tween(250)) + fadeOut(tween(250))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (clockWeather != null && !weatherAfter) {
                            AnimatedVisibility(visible = !showHaWidgets, enter = weatherEnter, exit = weatherExit) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ClockWeather(clockWeather)
                                    Spacer(Modifier.width(24.dp))
                                }
                            }
                        }
                        Text(
                            text = formatClockTime(currentTime, use24HourClock),
                            fontSize = 90.sp, fontWeight = FontWeight.ExtraLight, color = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.onSizeChanged { clockTimeWidth = it.width }
                        )
                        if (clockWeather != null && weatherAfter) {
                            AnimatedVisibility(visible = !showHaWidgets, enter = weatherEnter, exit = weatherExit) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Spacer(Modifier.width(24.dp))
                                    ClockWeather(clockWeather)
                                }
                            }
                        }
                    }
                    Text(
                        text = String.format(Locale.getDefault(), "%tA, %tB %te", currentTime, currentTime, currentTime).uppercase(),
                        fontSize = 16.sp, color = Color.White.copy(alpha = 0.3f), letterSpacing = 2.sp,
                        modifier = Modifier.onSizeChanged { clockDateWidth = it.width }
                    )
                }
            }
        }

        // The widgets come in from the left in step with the clock moving
        // right, like the controls opening on the other side. They can have
        // the whole width (the clock makes way if needed), scrolling if
        // there are more still.
        AnimatedVisibility(
            visible = showHaWidgets,
            enter = slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(300)),
            exit = slideOutHorizontally(tween(250, easing = FastOutSlowInEasing)) { -it } + fadeOut(tween(250)),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 24.dp, top = 40.dp)
                .widthIn(max = topWidth.coerceAtLeast(0.dp))
                .onSizeChanged { haWidgetsWidth = it.width }
                .alpha(fadeAlpha)
        ) {
            HaWidgetRow(
                haState = haState,
                onOpen = { openHaEntity = it },
                onHaCommand = onHaCommand,
                onReorder = onReorderHaWidgets
            )
        }

        if (albumHistory.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(start = 24.dp, top = 24.dp + topEndInset, end = 24.dp, bottom = 24.dp)
                    .alpha(fadeAlpha)
            ) {
                AnimatedVisibility(
                    visible = controlsOpen,
                    enter = expandHorizontally(tween(300), expandFrom = Alignment.End) + fadeIn(tween(300)),
                    exit = shrinkHorizontally(tween(250), shrinkTowards = Alignment.End) + fadeOut(tween(250))
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (categories.size > 1) {
                            Surface(
                                color = Color.Black.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(24.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(24.dp))
                                    .clickable {
                                        controlsTaps++
                                        category = categories[(categories.indexOf(category) + 1) % categories.size]
                                    }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                ) {
                                    Icon(category.icon, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "${category.label} · ${shownAlbums.size}",
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                        // Cycles All Genres -> each genre, A-Z. Only shown
                        // once some favorites have a genre.
                        if (genres.isNotEmpty()) {
                            Surface(
                                color = if (genre != null) Color.White.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(24.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(24.dp))
                                    .clickable {
                                        controlsTaps++
                                        val options = listOf<String?>(null) + genres
                                        genre = options[(options.indexOf(genre) + 1) % options.size]
                                    }
                                    .semantics { contentDescription = "Genre: ${genre ?: "all genres"}" }
                            ) {
                                val tint = if (genre != null) Color.Black else Color.White.copy(alpha = 0.85f)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                ) {
                                    Icon(Icons.Default.Category, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        genre ?: "All Genres",
                                        color = tint,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 180.dp)
                                    )
                                }
                            }
                        }
                        // Only albums have an artist to sort by.
                        if (showsAlbums) {
                            Surface(
                                color = Color.Black.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(24.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(24.dp))
                                    .clickable {
                                        controlsTaps++
                                        isSortByArtist = !isSortByArtist
                                        onSortChange(isSortByArtist)
                                    }
                                    .semantics { contentDescription = if (isSortByArtist) "Sorted by artist" else "Sorted by album" }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (isSortByArtist) "Artist" else "Album",
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                        run {
                            val spin = rememberInfiniteTransition(label = "refreshSpin")
                            val spinAngle by spin.animateFloat(
                                initialValue = 0f,
                                targetValue = 360f,
                                animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
                                label = "refreshAngle"
                            )
                            CoverFlowControlButton(
                                icon = Icons.Default.Refresh,
                                contentDescription = if (isRefreshingFavorites) "Refreshing favorites" else "Refresh favorites",
                                iconModifier = if (isRefreshingFavorites) Modifier.graphicsLayer { rotationZ = spinAngle } else Modifier
                            ) {
                                controlsTaps++
                                if (!isRefreshingFavorites) {
                                    refreshResult = null
                                    onRefreshFavorites { refreshResult = it }
                                }
                            }
                        }
                        CoverFlowControlButton(
                            icon = Icons.Default.Search,
                            contentDescription = if (searchOpen) "Close search" else "Search",
                            highlighted = searchOpen
                        ) {
                            controlsTaps++
                            if (searchOpen) closeSearch() else {
                                gridBeforeSearch = isGridView
                                isGridView = true
                                searchOpen = true
                            }
                        }
                        CoverFlowControlButton(
                            icon = if (isGridView) Icons.Default.ViewCarousel else Icons.Default.GridView,
                            contentDescription = if (isGridView) "Show as Cover Flow" else "Show as grid"
                        ) {
                            controlsTaps++
                            // Leaving the grid ends a search, which only narrows it.
                            if (searchOpen) closeSearch()
                            isGridView = !isGridView
                            onGridViewChange(isGridView)
                        }
                    }
                }
                CoverFlowControlButton(
                    icon = if (controlsOpen) Icons.Default.Close else Icons.Default.Tune,
                    contentDescription = if (controlsOpen) "Hide controls" else "Show filter, sort, refresh, search and view"
                ) {
                    if (controlsOpen && searchOpen) closeSearch()
                    controlsOpen = !controlsOpen
                }
            }
        }

        // Under the top-right controls: the refresh button's result. The
        // last one stays around so it can fade out with its text.
        var shownRefreshResult by remember { mutableStateOf<FavoritesRefreshResult?>(null) }
        refreshResult?.let { shownRefreshResult = it }
        AnimatedVisibility(
            visible = refreshResult != null,
            enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { -it / 2 },
            exit = fadeOut(tween(400)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 24.dp + topEndInset + 58.dp, end = 24.dp)
        ) {
            val result = shownRefreshResult
            if (result != null) {
                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            if (result.error != null) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (result.error != null) Color(0xFFFF8A80) else Color(0xFF81C784),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            when {
                                result.error != null -> "Couldn't refresh: ${result.error}"
                                result.added == 0 -> "No new favorites · ${result.total} loaded"
                                result.added == 1 -> "1 new favorite · ${result.total} loaded"
                                else -> "${result.added} new favorites · ${result.total} loaded"
                            },
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = searchOpen,
            enter = slideInVertically(tween(350)) { it } + fadeIn(tween(350)),
            exit = slideOutVertically(tween(300)) { it } + fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            SearchPanel(
                query = query,
                resultCount = gridAlbums.size,
                onQueryChange = { query = it },
                onClose = { closeSearch() }
            )
        }

        MetadataDebuggerOverlay(
            show = showDebugInfo,
            trackState = trackState,
            onClose = { showDebugInfo = false }
        )

        previousTrackDetails?.let { track ->
            TrackDetailsDialog(
                heading = "Previous Track",
                track = track,
                onDismiss = { previousTrackDetails = null },
                canReplay = canReplayTrack,
                // Back to Now Playing for it, like playing an album from here.
                onReplay = {
                    onReplayTrack(it)
                    onWakeUp()
                }
            )
        }

        openHaEntity?.let { haState.entities[it] }?.let { entity ->
            HaWidgetDialog(
                entity = entity,
                onHaCommand = onHaCommand,
                onHaCommandWithResult = onHaCommandWithResult,
                onDismiss = { openHaEntity = null }
            )
        }

        // Drawn in-tree rather than as an AlertDialog, since this screen
        // also runs inside SonosDreamService, which isn't an Activity.
        pendingFavoriteDelete?.let { item ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
                    // Swallows taps so they don't wake the screen; a tap
                    // outside the card cancels.
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { pendingFavoriteDelete = null })
                    }
            ) {
                Surface(
                    color = Color(0xFF1E1E1E),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .width(480.dp)
                        .pointerInput(Unit) { detectTapGestures { } }
                ) {
                    Column(modifier = Modifier.padding(28.dp)) {
                        Text(
                            if (item.onSonos) "Delete from Sonos Favorites?" else "Delete from the app?",
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            if (item.onSonos) {
                                "\"${item.albumTitle}\" will be permanently removed from your Sonos " +
                                    "Favorites, including in the Sonos app, and from this app. To keep it " +
                                    "here, remove it from Sonos in Settings > Favorites instead."
                            } else {
                                "\"${item.albumTitle}\" is no longer in your Sonos Favorites, so deleting " +
                                    "it here removes it for good."
                            },
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TextButton(onClick = { pendingFavoriteDelete = null }) {
                                Text("Cancel", color = Color.White)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = {
                                pendingFavoriteDelete = null
                                // The grid has no drop animation to run first.
                                if (isGridView) onDeleteFavorite(item) else confirmedFavoriteDelete = item
                            }) {
                                Text("Delete", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// Cover Flow's alternate view for big lists: rows of artwork, as many as fit
// below the clock, scrolling left and right, with an A-Z bar above to jump
// through them. A tap plays; in Favorites, a long press offers to delete.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverGrid(
    albums: List<AlbumHistoryItem>,
    bottomPadding: Dp,
    // The text each album is sorted by, whose first letter the A-Z bar uses.
    letterOf: (AlbumHistoryItem) -> String,
    // Whether an album's caption leads with its artist (sorted by artist).
    artistFirst: (AlbumHistoryItem) -> Boolean,
    showLetterBar: Boolean,
    emptyText: String,
    onPlay: (AlbumHistoryItem, Rect) -> Unit,
    onLongPress: ((AlbumHistoryItem) -> Unit)?,
    // Like Cover Flow cycling through its albums: once left untouched for a
    // while, drift slowly sideways with the A-Z bar hidden, so nothing sits
    // still long enough to burn in.
    autoScroll: Boolean = false,
    // Drift straight away instead, unless it's touched first.
    driftAtOnce: Boolean = false,
    onDriftStart: () -> Unit = {},
    // Where to start, if it was left there for a list this size, and a
    // callback as it scrolls.
    initialPosition: GridScrollPosition? = null,
    onPositionChanged: (GridScrollPosition) -> Unit = {},
    // Recently played favorites (favoriteKey -> when): while drifting, each
    // one passing the middle flips out to the front for FEATURE_HOLD_MS,
    // saying when it was last played, then flips back into its place.
    recentlyPlayed: Map<String, Long> = emptyMap(),
    // The favoriteKey of the one playing now, which says so instead.
    nowPlayingKey: String? = null,
    modifier: Modifier = Modifier
) {
    val animatedBottom by animateDpAsState(bottomPadding, tween(400), label = "gridBottom")
    val resumeAt = initialPosition?.takeIf { it.listSize == albums.size }
    val gridState = rememberLazyGridState(resumeAt?.index ?: 0, resumeAt?.offset ?: 0)
    val currentOnPositionChanged by rememberUpdatedState(onPositionChanged)
    LaunchedEffect(gridState, albums) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> currentOnPositionChanged(GridScrollPosition(index, offset, albums.size)) }
    }
    val scope = rememberCoroutineScope()
    val letters = remember(albums) { albums.map { indexLetter(letterOf(it)) } }
    // A new search or category starts from the beginning - but not the list
    // it opened on, which resumes where it was left.
    var shownList by remember { mutableStateOf(albums) }
    LaunchedEffect(albums) {
        if (albums != shownList) {
            shownList = albums
            gridState.scrollToItem(0)
        }
    }

    // The letter just picked on the A-Z bar: its albums get a gold border
    // until the grid starts drifting by itself.
    var highlightLetter by remember { mutableStateOf<Char?>(null) }
    // The A-Z bar's lit letter after a pick. Scrolling only ever lands on a
    // whole column, whose top album can be from the letter before (when the
    // picked letter starts lower down that column) - lighting the top
    // album's letter then made such letters look impossible to pick. Kept
    // until the grid is scrolled some other way.
    var pickedLetter by remember { mutableStateOf<Char?>(null) }
    // A re-sorted or different list has its letters in new places.
    LaunchedEffect(letters) {
        highlightLetter = null
        pickedLetter = null
    }
    val gridDragged by gridState.interactionSource.collectIsDraggedAsState()
    if (gridDragged && pickedLetter != null) pickedLetter = null

    // Lists long enough to fill the screen repeat end to end (as Cover Flow
    // wraps), so the grid can scroll forever. Search results don't.
    val endless = showLetterBar && albums.size > 24
    val itemCount = if (endless) albums.size * GRID_LOOP_COPIES else albums.size

    // Any touch stops the drift and brings the A-Z bar back; it starts
    // again once the grid has been left alone for a while.
    var lastTouch by remember { mutableLongStateOf(0L) }
    var drifting by remember { mutableStateOf(false) }
    val driftPxPerSecond = with(LocalDensity.current) { GridDriftSpeed.toPx() }
    LaunchedEffect(autoScroll, endless, albums, lastTouch) {
        drifting = false
        if (!autoScroll || !endless) return@LaunchedEffect
        delay(if (driftAtOnce && lastTouch == 0L) START_SCROLLING_DELAY_MS else GRID_DRIFT_DELAY_MS)
        drifting = true
        onDriftStart()
        pickedLetter = null
        highlightLetter = null
        var lastFrame = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (lastFrame != 0L) gridState.scrollBy((now - lastFrame) / 1_000_000_000f * driftPxPerSecond)
            lastFrame = now
            // Well before the copies run out, jump back by a whole number
            // of lists and columns - the same albums in the same places, so
            // the jump can't be seen.
            val first = gridState.firstVisibleItemIndex
            if (first > itemCount / 2) {
                val rows = (gridState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.row } ?: 0) + 1
                val period = lcm(albums.size, rows)
                gridState.scrollToItem(first % period, gridState.firstVisibleItemScrollOffset)
            }
        }
    }

    // Album titles and artists fade out while drifting, back in on a touch.
    val captionAlpha by animateFloatAsState(
        targetValue = if (drifting) 0f else 1f,
        animationSpec = tween(if (drifting) 1200 else 400),
        label = "gridCaptions"
    )

    // The recently played favorite out at the front (its index in the
    // grid, whose place is left empty meanwhile), where that place is now,
    // and how far out it is (0 = in its place, 1 = at the front). Each
    // copy of the list in an endless grid gets its own turn.
    var featuredIndex by remember { mutableStateOf<Int?>(null) }
    var featuredBounds by remember { mutableStateOf(Rect.Zero) }
    val featureProgress = remember { Animatable(0f) }
    val featuredAlready = remember(albums) { mutableSetOf<Int>() }
    val isRecent = remember(albums, recentlyPlayed) { albums.map { favoriteKey(it) in recentlyPlayed } }
    LaunchedEffect(drifting, isRecent) {
        // Whatever's out goes back first - straight away after a touch.
        if (featuredIndex != null) {
            featureProgress.animateTo(0f, tween(if (drifting) FEATURE_FLIP_MS else 400, easing = FastOutSlowInEasing))
        }
        featuredIndex = null
        if (!drifting || isRecent.none { it }) return@LaunchedEffect
        while (true) {
            delay(500)
            val info = gridState.layoutInfo
            val middle = info.viewportSize.width * 0.25f..info.viewportSize.width * 0.75f
            val next = info.visibleItemsInfo.firstOrNull { item ->
                item.index !in featuredAlready && isRecent[item.index % albums.size] &&
                    (item.offset.x + item.size.width / 2f) in middle
            } ?: continue
            featuredAlready += next.index
            featuredIndex = next.index
            featureProgress.snapTo(0f)
            featureProgress.animateTo(1f, tween(FEATURE_FLIP_MS, easing = FastOutSlowInEasing))
            delay(FEATURE_HOLD_MS)
            featureProgress.animateTo(0f, tween(FEATURE_FLIP_MS, easing = FastOutSlowInEasing))
            featuredIndex = null
        }
    }
    var gridOrigin by remember { mutableStateOf(Offset.Zero) }
    var gridSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .onGloballyPositioned {
                gridOrigin = it.boundsInWindow().topLeft
                gridSize = it.size
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    lastTouch = System.currentTimeMillis()
                }
            }
    ) {
        if (albums.isEmpty()) {
            Text(
                emptyText,
                color = Color.Gray,
                fontSize = 24.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        LazyHorizontalGrid(
            state = gridState,
            rows = GridCells.Adaptive(minSize = 190.dp),
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = GridTop, bottom = animatedBottom),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(itemCount) { index ->
                val album = albums[index % albums.size]
                var bounds by remember { mutableStateOf(Rect.Zero) }
                val highlightAlpha by animateFloatAsState(
                    targetValue = if (highlightLetter != null && letters[index % albums.size] == highlightLetter) 1f else 0f,
                    animationSpec = tween(400),
                    label = "letterHighlight"
                )
                // Out at the front: its place is left empty, just the frame.
                val featuredOut = index == featuredIndex
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f)
                        .onGloballyPositioned {
                            bounds = it.boundsInWindow()
                            if (index == featuredIndex) featuredBounds = bounds
                        }
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RectangleShape)
                        .border(3.dp, GridHighlightGold.copy(alpha = highlightAlpha), RectangleShape)
                        .combinedClickable(
                            onClick = { onPlay(album, bounds) },
                            onLongClick = onLongPress?.let { longPress -> { longPress(album) } }
                        )
                ) {
                    AsyncImage(
                        model = album.artUrl,
                        contentDescription = album.albumTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(if (featuredOut) 0f else 1f)
                            .background(Color.White.copy(alpha = 0.05f))
                    )
                    // Title and artist on a dark band along the bottom of the
                    // art, so they stay readable over artwork with writing
                    // on it. Hidden while the grid drifts by itself, so
                    // only the artwork is on show then.
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .graphicsLayer { alpha = if (featuredOut) 0f else captionAlpha }
                            .background(Color.Black.copy(alpha = 0.65f))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        val byArtist = artistFirst(album)
                        val second = if (byArtist) album.albumTitle else album.artist
                        Text(
                            if (byArtist) album.artist else album.albumTitle,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (second.isNotBlank()) {
                            Text(
                                second,
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        featuredIndex?.let { index ->
            FeaturedRecentAlbum(
                album = albums[index % albums.size],
                playedAt = recentlyPlayed[favoriteKey(albums[index % albums.size])],
                playingNow = nowPlayingKey != null && favoriteKey(albums[index % albums.size]) == nowPlayingKey,
                // Where its place in the grid is now, and the front: the
                // middle of the space the grid's rows take up.
                from = featuredBounds.translate(-gridOrigin),
                areaTop = with(LocalDensity.current) { GridTop.toPx() },
                areaBottom = gridSize.height - with(LocalDensity.current) { animatedBottom.toPx() },
                areaWidth = gridSize.width.toFloat(),
                progress = featureProgress.value,
                onPlay = { onPlay(it, featuredBounds) }
            )
        }

        // After the grid, so it sits on top: the grid fills the whole screen
        // (its top padding included), and drawn underneath it the bar's taps
        // went to the grid instead and fell through to waking Now Playing.
        // While the bar is hidden, a tap where it sits brings it back (the
        // touch above stops the drift) instead of waking Now Playing.
        if (drifting) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = GridLetterBarTop, start = 48.dp, end = 48.dp)
                    .widthIn(max = 980.dp)
                    .fillMaxWidth()
                    .height(36.dp)
                    .pointerInput(Unit) { detectTapGestures { } }
            )
        }
        AnimatedVisibility(
            visible = showLetterBar && albums.size > 24 && !drifting,
            enter = fadeIn(tween(600)),
            exit = fadeOut(tween(1200)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = GridLetterBarTop, start = 48.dp, end = 48.dp)
        ) {
            val scrolledTo by remember(letters) {
                derivedStateOf { letters.getOrNull(gridState.firstVisibleItemIndex % letters.size.coerceAtLeast(1)) }
            }
            val current = pickedLetter ?: scrolledTo
            LetterBar(
                present = remember(letters) { letters.toSet() },
                current = current,
                onLetter = { letter ->
                    // The first album at that letter, or the next one there is.
                    val target = INDEX_LETTERS.indexOf(letter)
                    val index = letters.indexOfFirst { INDEX_LETTERS.indexOf(it) >= target }.takeIf { it >= 0 } ?: letters.lastIndex
                    scope.launch { gridState.scrollToItem(index) }
                    // The letter actually landed on, which for a dimmed
                    // letter is the next one that has albums.
                    highlightLetter = letters.getOrNull(index)
                    pickedLetter = letters.getOrNull(index)
                }
            )
        }
    }
}

private val GridLetterBarTop = 150.dp

// A recently played favorite's flip out to the front and back, and how long
// it stays there.
private const val FEATURE_FLIP_MS = 900
private const val FEATURE_HOLD_MS = 3_000L

// The drifting grid's recently played favorite on its way to or from the
// front (progress 0 = in its place at from, 1 = at the front): growing and
// turning over once as it goes, over a dimmed grid, with when it was last
// played underneath once it's there. A tap plays it.
@Composable
private fun FeaturedRecentAlbum(
    album: AlbumHistoryItem,
    playedAt: Long?,
    // Says "Now Playing" instead of when it was last played.
    playingNow: Boolean,
    from: Rect,
    areaTop: Float,
    areaBottom: Float,
    areaWidth: Float,
    progress: Float,
    onPlay: (AlbumHistoryItem) -> Unit
) {
    val density = LocalDensity.current
    val captionRoom = with(density) { 84.dp.toPx() }
    val frontSize = minOf(from.width * 2.2f, areaBottom - areaTop - captionRoom).coerceAtLeast(from.width)
    val frontLeft = (areaWidth - frontSize) / 2f
    val frontTop = areaTop + (areaBottom - areaTop - frontSize - captionRoom) / 2f
    val size = lerp(from.width, frontSize, progress)
    val left = lerp(from.left, frontLeft, progress)
    val top = lerp(from.top, frontTop, progress)

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = progress }
            .background(Color.Black.copy(alpha = 0.45f))
    )
    AsyncImage(
        model = album.artUrl,
        contentDescription = album.albumTitle,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(with(density) { size.toDp() })
            .graphicsLayer {
                rotationY = 360f * progress
                cameraDistance = 16f * density.density
                shadowElevation = 24.dp.toPx() * progress
            }
            .clickable { onPlay(album) }
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .offset { IntOffset(frontLeft.roundToInt(), (frontTop + frontSize).roundToInt()) }
            .width(with(density) { frontSize.toDp() })
            .padding(top = 14.dp)
            // Only once it's (nearly) at the front.
            .graphicsLayer { alpha = ((progress - 0.7f) / 0.3f).coerceIn(0f, 1f) }
    ) {
        Text(
            album.albumTitle,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (playingNow) {
            Text("Now Playing", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, maxLines = 1)
        } else if (playedAt != null) {
            val ago = DateUtils.getRelativeTimeSpanString(playedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
            Text(
                "Last played ${if (ago == "Yesterday") "yesterday" else ago}",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 15.sp,
                maxLines = 1
            )
        }
    }
}
private val GridTop = 196.dp

// How many times an endless grid repeats its list, how long it sits
// untouched before drifting (Cover Flow's own auto-advance interval), and
// how fast it drifts.
private const val GRID_LOOP_COPIES = 50
private const val GRID_DRIFT_DELAY_MS = 8_000L
// ...or, with startScrolling, just long enough for the screen to slide in.
private const val START_SCROLLING_DELAY_MS = 1_000L
private val GridDriftSpeed = 24.dp

// The A-Z bar's gold border round the picked letter's albums.
private val GridHighlightGold = Color(0xFFFFC94D)

private fun lcm(a: Int, b: Int): Int {
    tailrec fun gcd(x: Int, y: Int): Int = if (y == 0) x else gcd(y, x % y)
    return a / gcd(a, b) * b
}

// "#" for anything that doesn't start with a letter.
private val INDEX_LETTERS = listOf('#') + ('A'..'Z')

// The A-Z bar letter for a title/artist: its first letter with accents
// dropped ("Édith" -> E), or # for digits and symbols.
private fun indexLetter(text: String): Char {
    val first = Normalizer.normalize(text.trim().take(1), Normalizer.Form.NFD).firstOrNull()?.uppercaseChar() ?: return '#'
    return if (first in 'A'..'Z') first else '#'
}

// # and A-Z across the top of the grid. Touch a letter, or slide along the
// bar to scrub through them; the letter under the finger swells up out of
// the bar (its neighbours a little) so it's clear which one is picked.
// Letters with no albums are dimmed (and jump to the next one that has some).
@Composable
private fun LetterBar(
    present: Set<Char>,
    current: Char?,
    onLetter: (Char) -> Unit,
    modifier: Modifier = Modifier
) {
    var width by remember { mutableIntStateOf(0) }
    // The touch handler below is set up once, so it must reach the latest
    // onLetter - an old one would still jump and highlight using the list
    // as it was sorted when the bar first appeared.
    val currentOnLetter by rememberUpdatedState(onLetter)
    // Where the finger is along the bar while it's down, else null.
    var touchX by remember { mutableStateOf<Float?>(null) }
    fun slotAt(x: Float): Float = x / width.coerceAtLeast(1) * INDEX_LETTERS.size
    Box(
        modifier = modifier
            .widthIn(max = 980.dp)
            .fillMaxWidth()
            .height(36.dp)
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
            .onGloballyPositioned { width = it.size.width }
            // One handler for both taps and slides: the letter follows the
            // finger from the moment it lands until it lifts, so a tap that
            // shifts a little picks where it ended rather than two letters.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var lastLetter: Char? = null
                    fun follow(x: Float) {
                        touchX = x
                        val letter = INDEX_LETTERS[slotAt(x).toInt().coerceIn(0, INDEX_LETTERS.lastIndex)]
                        if (letter != lastLetter) {
                            lastLetter = letter
                            currentOnLetter(letter)
                        }
                    }
                    follow(down.position.x)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        change.consume()
                        follow(change.position.x)
                    }
                    touchX = null
                }
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize()) {
            INDEX_LETTERS.forEachIndexed { i, letter ->
                val isCurrent = letter == current
                // 2.2x right under the finger, falling off over the next two
                // letters each side.
                val distance = touchX?.let { abs(slotAt(it) - (i + 0.5f)) }
                val targetScale = if (distance == null) 1f else 1f + 1.2f * (1f - distance / 2.5f).coerceAtLeast(0f)
                val scale by animateFloatAsState(targetScale, spring(stiffness = Spring.StiffnessMediumLow), label = "letterScale")
                Text(
                    letter.toString(),
                    color = when {
                        isCurrent -> Color.Black
                        letter in present -> Color.White.copy(alpha = 0.85f)
                        else -> Color.White.copy(alpha = 0.25f)
                    },
                    fontSize = 14.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .zIndex(scale)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            // Rise out of the bar as it grows, clear of the finger.
                            translationY = -(scale - 1f) * 22.dp.toPx()
                        }
                        .padding(horizontal = 1.dp)
                        .background(if (isCurrent) Color.White.copy(alpha = 0.85f) else Color.Transparent, CircleShape)
                        .padding(vertical = 4.dp)
                )
            }
        }
    }
}

// Every word typed must appear in the title or artist, ignoring case and
// accents - so "beat abb" finds Abbey Road by The Beatles.
private fun filterBySearch(albums: List<AlbumHistoryItem>, query: String): List<AlbumHistoryItem> {
    val words = searchKey(query).split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return albums
    return albums.filter { album ->
        val haystack = searchKey("${album.albumTitle} ${album.artist}")
        words.all { it in haystack }
    }
}

private fun searchKey(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

private val SearchPanelHeight = 372.dp

// Cover Flow search along the bottom of the screen: what's been typed, how
// many albums match, and the app's own keyboard.
@Composable
private fun SearchPanel(
    query: String,
    resultCount: Int,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    Surface(
        color = Color(0xFF1C1C1F).copy(alpha = 0.96f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        modifier = Modifier
            .fillMaxWidth()
            .height(SearchPanelHeight)
            // Taps between the keys mustn't fall through and wake the screen.
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth()
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.6f))
                Spacer(Modifier.width(10.dp))
                Text(
                    query.ifEmpty { "Search titles and artists" },
                    color = Color.White.copy(alpha = if (query.isEmpty()) 0.4f else 1f),
                    fontSize = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (query.isNotEmpty()) {
                    Text(
                        if (resultCount == 1) "1 match" else "$resultCount matches",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 14.sp
                    )
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = Color.White.copy(alpha = 0.7f))
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close search", tint = Color.White)
                }
            }
            Spacer(Modifier.height(10.dp))
            DockKeyboard(
                onKey = { char ->
                    // No leading or doubled spaces.
                    if (char != ' ' || (query.isNotEmpty() && !query.endsWith(' '))) onQueryChange(query + char.lowercaseChar())
                },
                onBackspace = { onQueryChange(query.dropLast(1)) },
                keyHeight = 44.dp
            )
        }
    }
}

// Beside the ambient clock's time: the first weather widget's condition icon
// and outside temperature, faint like the clock itself.
@Composable
private fun ClockWeather(weather: HaWeatherState) {
    val night = weather.sun?.let { !it.isUp } ?: false
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(weatherIconRes(weather.condition, night)),
            contentDescription = null,
            tint = weatherTint(weather.condition, night).copy(alpha = 0.6f),
            modifier = Modifier.size(36.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "${weather.temperature?.roundToInt()}°",
            fontSize = 28.sp,
            fontWeight = FontWeight.Light,
            color = Color.White.copy(alpha = 0.4f)
        )
    }
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float {
    return (1 - fraction) * start + fraction * stop
}
