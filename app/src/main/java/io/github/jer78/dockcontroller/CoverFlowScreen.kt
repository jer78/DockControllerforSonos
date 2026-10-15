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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
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
import java.text.Normalizer
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

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

// Matched by identity rather than index, since History mode reorders the
// list (a played album moves to the front). playUri is the stable ID when
// present; history items without one are identified by their art URL.
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
    onRemoveAlbum: (String) -> Unit,
    onWakeUp: () -> Unit,
    use24HourClock: Boolean = true,
    // 0..1 each, from Settings - see SettingsManager.COVER_FLOW_REFLECTION.
    reflectionStrength: Float = SettingsManager.COVER_FLOW_REFLECTION_DEFAULT,
    glassTableStrength: Float = SettingsManager.COVER_FLOW_GLASS_TABLE_DEFAULT,
    sideLightStrength: Float = SettingsManager.COVER_FLOW_SIDE_LIGHT_DEFAULT,
    // In Favorites mode the X permanently deletes the Sonos Favorite (after
    // a confirmation prompt) instead of just hiding the card.
    isFavoritesMode: Boolean = false,
    onDeleteFavorite: (AlbumHistoryItem) -> Unit = {},
    // Album to start centered on, and a callback whenever a new one settles
    // in the center - lets the caller resume the rotation where it left off.
    initialCenterAlbum: AlbumHistoryItem? = null,
    onCenterAlbumChanged: (AlbumHistoryItem) -> Unit = {},
    // Settings > Display > Cover Flow Track Cards.
    hidePrevious: Boolean = false,
    hideNextUp: Boolean = false,
    hideNowPlaying: Boolean = false,
    // The first Home Assistant weather widget's entity, shown under the
    // clock's date when there is one.
    weather: HaWeatherState? = null
) {
    var isFadingOut by remember { mutableStateOf(false) }
    // Favorite awaiting the user's confirm/cancel, and then the one they
    // confirmed (which drives that card's drop animation before deleting).
    var pendingFavoriteDelete by remember { mutableStateOf<AlbumHistoryItem?>(null) }
    var confirmedFavoriteDelete by remember { mutableStateOf<AlbumHistoryItem?>(null) }

    // The top-right controls: which kind of favorite to show, and Cover Flow
    // or a side-scrolling grid. Both reset each time Cover Flow opens, so
    // it always starts on every favorite, in Cover Flow.
    var category by remember { mutableStateOf(FavoriteCategory.ALL) }
    var isGridView by remember { mutableStateOf(false) }
    // Only kinds that actually have favorites are offered.
    val categories = remember(albumHistory, isFavoritesMode) {
        if (!isFavoritesMode) listOf(FavoriteCategory.ALL)
        else listOf(FavoriteCategory.ALL) + FavoriteCategory.entries.filter { c -> c.kind != null && albumHistory.any { it.kind == c.kind } }
    }
    if (category !in categories) category = FavoriteCategory.ALL
    val shownAlbums = remember(albumHistory, category) {
        category.kind?.let { kind -> albumHistory.filter { it.kind == kind } } ?: albumHistory
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

    LaunchedEffect(isUserInteracting, isDragged) {
        if (isUserInteracting || isDragged) {
            showLabels = true
        } else {
            delay(5000) 
            showLabels = false
        }
    }

    LaunchedEffect(shownAlbums, isUserInteracting, isDragged, pendingFavoriteDelete, isGridView) {
        if (shownAlbums.isNotEmpty() && pendingFavoriteDelete == null && !isGridView) {
            while (true) {
                delay(8000)
                if (pagerState.pageCount > 0 && !isUserInteracting && !isDragged && !pagerState.isScrollInProgress) {
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
                    trackState.isPlaying && !(hidePrevious && hideNowPlaying && hideNextUp) -> 150.dp
                    else -> 32.dp
                },
                // History is sorted by artist, Favorites by title.
                letterOf = { if (isFavoritesMode) it.albumTitle else it.artist },
                showLetterBar = !searchOpen,
                emptyText = "No matches for \"${query.trim()}\"",
                onPlay = { album, bounds ->
                    isFadingOut = true
                    onPlayAlbum(album, bounds)
                },
                onLongPress = if (isFavoritesMode) ({ pendingFavoriteDelete = it }) else null,
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
                            if (isFavoritesMode && confirmedFavoriteDelete == albumItem) {
                                confirmedFavoriteDelete = null
                                onDeleteFavorite(albumItem)
                            } else {
                                onRemoveAlbum(albumItem.artUrl)
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
                                                if (isFavoritesMode) pendingFavoriteDelete = albumItem
                                                else isBeingRemoved.value = true
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
                                Text(
                                    text = albumItem.albumTitle,
                                    color = Color.White,
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = albumItem.artist,
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

        AnimatedVisibility(
            visible = showPreviousCard,
            enter = fadeIn(tween(600)),
            exit = fadeOut(tween(600)),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(24.dp)
                .alpha(fadeAlpha)
        ) {
            CoverFlowTrackCard(
                label = "PREVIOUS",
                title = trackState.prevTrack,
                subtitle = trackState.prevArtist,
                artUrl = trackState.prevArtUrl,
                onClick = { previousTrackDetails = trackState.previousTrackDetails() }
            )
        }

        if (showNowPlayingCard || showNextUpCard) {
            var nextUpTurn by remember { mutableStateOf(false) }
            // Restarts on every track change, so a new song always opens on
            // its own Now Playing card first.
            LaunchedEffect(showNowPlayingCard, showNextUpCard, trackState.title) {
                nextUpTurn = false
                if (showNowPlayingCard && showNextUpCard) {
                    while (true) {
                        delay(12_000)
                        nextUpTurn = true
                        delay(6_000)
                        nextUpTurn = false
                    }
                }
            }
            val showingNextUp = showNextUpCard && (nextUpTurn || !showNowPlayingCard)

            AnimatedContent(
                targetState = showingNextUp,
                transitionSpec = {
                    (slideInHorizontally(tween(700, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(700))) togetherWith
                        (slideOutHorizontally(tween(700, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(500)))
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
                    .alpha(fadeAlpha),
                label = "nowPlayingNextUp"
            ) { nextUp ->
                if (nextUp) {
                    CoverFlowTrackCard(
                        label = "NEXT UP",
                        title = trackState.nextTrack,
                        subtitle = trackState.nextArtist,
                        artUrl = trackState.nextArtUrl,
                        onClick = onWakeUp
                    )
                } else {
                    CoverFlowTrackCard(
                        label = "NOW PLAYING",
                        title = trackState.title,
                        subtitle = if (trackState.album.isNotBlank()) "${trackState.artist} • ${trackState.album}" else trackState.artist,
                        artUrl = trackState.albumArtUrl,
                        onClick = onWakeUp
                    )
                }
            }
        }

        AnimatedContent(
            targetState = clockAlignment,
            transitionSpec = { fadeIn(tween(1500)) togetherWith fadeOut(tween(1500)) },
            modifier = Modifier.fillMaxSize().padding(top = 20.dp, start = 60.dp, end = 60.dp).alpha(fadeAlpha),
            label = "clockMove"
        ) { alignment ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = alignment) {
                Column(
                    horizontalAlignment = when {
                        alignment.toString().contains("End") -> Alignment.End
                        alignment.toString().contains("Start") -> Alignment.Start
                        else -> Alignment.CenterHorizontally
                    }
                ) {
                    Text(
                        text = formatClockTime(currentTime, use24HourClock),
                        fontSize = 90.sp, fontWeight = FontWeight.ExtraLight, color = Color.White.copy(alpha = 0.5f)
                    )
                    Text(
                        text = String.format(Locale.getDefault(), "%tA, %tB %te", currentTime, currentTime, currentTime).uppercase(),
                        fontSize = 16.sp, color = Color.White.copy(alpha = 0.3f), letterSpacing = 2.sp
                    )
                    weather?.takeIf { it.temperature != null }?.let { ClockWeather(it) }
                }
            }
        }

        if (albumHistory.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp)
                    .alpha(fadeAlpha)
            ) {
                if (categories.size > 1) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .clickable { category = categories[(categories.indexOf(category) + 1) % categories.size] }
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
                Surface(
                    color = if (searchOpen) Color.White.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.55f),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                    modifier = Modifier.clip(CircleShape).clickable {
                        if (searchOpen) closeSearch() else {
                            gridBeforeSearch = isGridView
                            isGridView = true
                            searchOpen = true
                        }
                    }
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = if (searchOpen) "Close search" else "Search",
                        tint = if (searchOpen) Color.Black else Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.padding(12.dp).size(22.dp)
                    )
                }
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                    modifier = Modifier.clip(CircleShape).clickable {
                        // Leaving the grid ends a search, which only narrows it.
                        if (searchOpen) closeSearch()
                        isGridView = !isGridView
                    }
                ) {
                    Icon(
                        if (isGridView) Icons.Default.ViewCarousel else Icons.Default.GridView,
                        contentDescription = if (isGridView) "Show as Cover Flow" else "Show as grid",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.padding(12.dp).size(22.dp)
                    )
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
                onDismiss = { previousTrackDetails = null }
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
                            "Delete from Sonos Favorites?",
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "\"${item.albumTitle}\" will be permanently removed from your Sonos " +
                                "Favorites, including in the Sonos app. This can't be undone here.",
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
    showLetterBar: Boolean,
    emptyText: String,
    onPlay: (AlbumHistoryItem, Rect) -> Unit,
    onLongPress: ((AlbumHistoryItem) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val animatedBottom by animateDpAsState(bottomPadding, tween(400), label = "gridBottom")
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val letters = remember(albums) { albums.map { indexLetter(letterOf(it)) } }
    // A new search or category starts from the beginning.
    LaunchedEffect(albums) { gridState.scrollToItem(0) }

    Box(modifier = modifier) {
        if (showLetterBar && albums.size > 24) {
            val current by remember(letters) {
                derivedStateOf { letters.getOrNull(gridState.firstVisibleItemIndex) }
            }
            LetterBar(
                present = remember(letters) { letters.toSet() },
                current = current,
                onLetter = { letter ->
                    // The first album at that letter, or the next one there is.
                    val target = INDEX_LETTERS.indexOf(letter)
                    val index = letters.indexOfFirst { INDEX_LETTERS.indexOf(it) >= target }.takeIf { it >= 0 } ?: letters.lastIndex
                    scope.launch { gridState.scrollToItem(index) }
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = GridLetterBarTop, start = 48.dp, end = 48.dp)
            )
        }

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
            items(albums) { album ->
                var bounds by remember { mutableStateOf(Rect.Zero) }
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f)
                        .onGloballyPositioned { bounds = it.boundsInWindow() }
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RectangleShape)
                        .combinedClickable(
                            onClick = { onPlay(album, bounds) },
                            onLongClick = onLongPress?.let { longPress -> { longPress(album) } }
                        )
                ) {
                    AsyncImage(
                        model = album.artUrl,
                        contentDescription = album.albumTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.05f))
                    )
                    // Title and artist over a shade along the bottom of the art.
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                            .padding(start = 10.dp, end = 10.dp, top = 24.dp, bottom = 8.dp)
                    ) {
                        Text(
                            album.albumTitle,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (album.artist.isNotBlank()) {
                            Text(
                                album.artist,
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
    }
}

private val GridLetterBarTop = 150.dp
private val GridTop = 196.dp

// "#" for anything that doesn't start with a letter.
private val INDEX_LETTERS = listOf('#') + ('A'..'Z')

// The A-Z bar letter for a title/artist: its first letter with accents
// dropped ("Édith" -> E), or # for digits and symbols.
private fun indexLetter(text: String): Char {
    val first = Normalizer.normalize(text.trim().take(1), Normalizer.Form.NFD).firstOrNull()?.uppercaseChar() ?: return '#'
    return if (first in 'A'..'Z') first else '#'
}

// # and A-Z across the top of the grid. Tap a letter, or drag along the bar
// to scrub through them; letters with no albums are dimmed (and jump to the
// next one that has some).
@Composable
private fun LetterBar(
    present: Set<Char>,
    current: Char?,
    onLetter: (Char) -> Unit,
    modifier: Modifier = Modifier
) {
    var width by remember { mutableIntStateOf(0) }
    var lastLetter by remember { mutableStateOf<Char?>(null) }
    fun letterAt(x: Float): Char {
        val i = (x / width.coerceAtLeast(1) * INDEX_LETTERS.size).toInt().coerceIn(0, INDEX_LETTERS.lastIndex)
        return INDEX_LETTERS[i]
    }
    fun pick(x: Float) {
        val letter = letterAt(x)
        if (letter != lastLetter) {
            lastLetter = letter
            onLetter(letter)
        }
    }
    Surface(
        color = Color.Black.copy(alpha = 0.45f),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .widthIn(max = 980.dp)
            .fillMaxWidth()
            .height(36.dp)
            .onGloballyPositioned { width = it.size.width }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    lastLetter = null
                    pick(offset.x)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        lastLetter = null
                        pick(offset.x)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        pick(change.position.x)
                    }
                )
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            INDEX_LETTERS.forEach { letter ->
                val isCurrent = letter == current
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

// Under the ambient clock's date: the first weather widget's condition and
// temperature, as faint as the date itself.
@Composable
private fun ClockWeather(weather: HaWeatherState) {
    val night = weather.sun?.let { !it.isUp } ?: false
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
        Icon(
            painterResource(weatherIconRes(weather.condition, night)),
            contentDescription = null,
            tint = weatherTint(weather.condition, night).copy(alpha = 0.6f),
            modifier = Modifier.size(26.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "${weather.temperature?.roundToInt()}°  ${weatherConditionLabel(weather.condition).uppercase()}",
            fontSize = 16.sp,
            color = Color.White.copy(alpha = 0.4f),
            letterSpacing = 2.sp
        )
    }
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float {
    return (1 - fraction) * start + fraction * stop
}
