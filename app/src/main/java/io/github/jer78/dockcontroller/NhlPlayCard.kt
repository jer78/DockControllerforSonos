package io.github.jer78.dockcontroller

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.delay

private val GoalColor = Color(0xFFFFC94D)
private val PenaltyColor = Color(0xFFFF8A50)
private val CardBackground = Color(0xFF121519)

// A goal, the penalties being served, or an intermission / final summary
// from the NHL game on TV (see NhlGameWatcher): the screen (content) slides
// off to the right as the play slides in, full screen, from the left - and
// back once it's closed with the X, the penalties end, or play resumes.
// card: null when there's nothing to show.
@Composable
fun NhlPlayScreenHost(card: NhlCard?, onClose: () -> Unit, content: @Composable () -> Unit) {
    // Kept while it slides away, after card has gone.
    var shown by remember { mutableStateOf(card) }
    if (card != null) shown = card
    val progress by animateFloatAsState(
        targetValue = if (card != null) 1f else 0f,
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "nhlSlide"
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        Box(Modifier.fillMaxSize().graphicsLayer { translationX = progress * width }) { content() }
        val play = shown
        if (progress > 0f && play != null) {
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = (progress - 1f) * width }) {
                NhlPlayScreen(play, onClose)
            }
        }
    }
}

@Composable
private fun NhlPlayScreen(card: NhlCard, onClose: () -> Unit) {
    val (main, second) = titleColors(card)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(lerp(main, Color.Black, 0.72f), Color(0xFF07080A))))
            // Taps stay here, rather than reaching the screen underneath.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    ) {
        // A new play while one's up fades in over it.
        AnimatedContent(
            targetState = card,
            contentKey = { it.key },
            transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(400)) },
            label = "nhlPlay",
            modifier = Modifier.fillMaxSize()
        ) { play ->
            if (play is NhlCard.Summary) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { SummaryCard(play) }
                return@AnimatedContent
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)
            ) {
                val title = when {
                    play is NhlCard.Goal -> "GOAL!"
                    (play as NhlCard.Penalty).calls.size > 1 -> "PENALTIES"
                    else -> "PENALTY"
                }
                AnimatedTitle(title, main, second, logoUrl = titleLogo(play), key = play.key)
                MatchupLine(play.score, subtitle = (play as? NhlCard.Goal)?.let { listOfNotNull(it.teamAbbrev, it.strength).joinToString(" · ") })
                Spacer(Modifier.height(24.dp))
                // A Column, not a Box: the goal's details are several pieces
                // (scorer, then assists) that stack.
                Column(
                    Modifier
                        .widthIn(max = 1040.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .padding(horizontal = 36.dp, vertical = 28.dp)
                ) {
                    when (play) {
                        is NhlCard.Goal -> GoalBody(play)
                        is NhlCard.Penalty -> PenaltyBody(play)
                        else -> {}
                    }
                }
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(56.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(32.dp))
        }
    }
}

// The title's colours: the scoring team's for a goal, the penalized team's
// for penalties (orange when both sides took one).
private fun titleColors(card: NhlCard): Pair<Color, Color> = when (card) {
    is NhlCard.Goal -> teamColors(card.teamAbbrev)
    is NhlCard.Penalty -> card.calls.map { it.teamAbbrev }.distinct().singleOrNull()?.let { teamColors(it) }
        ?: (PenaltyColor to Color(0xFFC8102E))
    is NhlCard.Summary -> GoalColor to PenaltyColor
}

// The logo beside the title: the scoring team's, or the penalized team's
// when only one side took the penalties.
private fun titleLogo(card: NhlCard): String? {
    val team = when (card) {
        is NhlCard.Goal -> card.teamAbbrev
        is NhlCard.Penalty -> card.calls.map { it.teamAbbrev }.distinct().singleOrNull()
        is NhlCard.Summary -> null
    } ?: return null
    return when (team) {
        card.score.homeAbbrev -> card.score.homeLogo
        card.score.awayAbbrev -> card.score.awayLogo
        else -> null
    }?.takeIf { it.isNotBlank() }
}

// A huge title that pops in, with the team's colours (and a flash of white)
// sweeping through it - and the team's logo, about as tall, to its left.
@OptIn(ExperimentalTextApi::class)
@Composable
private fun AnimatedTitle(text: String, main: Color, second: Color, logoUrl: String?, key: String) {
    val pop = remember(key) { Animatable(0.2f) }
    LaunchedEffect(key) {
        pop.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessLow))
    }
    val sweep by rememberInfiniteTransition(label = "titleSweep").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "titleSweepOffset"
    )
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val width = constraints.maxWidth.toFloat()
        // A band of colours twice the screen's width, sliding across.
        val start = (sweep - 1f) * 2f * width
        val brush = Brush.linearGradient(
            colors = listOf(main, lighten(second), Color.White, main, lighten(second), main),
            start = Offset(start, 0f),
            end = Offset(start + 2f * width, width * 0.15f),
            tileMode = TileMode.Repeated
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            }
        ) {
            if (logoUrl != null) {
                TeamLogo(logoUrl, 150.dp)
                Spacer(Modifier.width(28.dp))
            }
            Text(
                text,
                style = TextStyle(
                    brush = brush,
                    fontSize = 150.sp,
                    fontWeight = FontWeight.Black,
                    fontStyle = FontStyle.Italic,
                    letterSpacing = 6.sp
                ),
                maxLines = 1
            )
        }
    }
}

// A dark second colour (black, navy) would vanish against the background.
private fun lighten(color: Color): Color =
    if (color.red + color.green + color.blue < 0.6f) lerp(color, Color.White, 0.55f) else color

// Like the summary's: each side's logo with its score beside it, big, and
// between them the teams, the period's clock and the power play.
@Composable
private fun MatchupLine(score: NhlScore, subtitle: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(score.awayLogo, 88.dp)
        SummaryScore(score.awayScore, Modifier.padding(start = 16.dp), size = 72)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 40.dp)) {
            Text(
                "${score.awayAbbrev}  ·  ${score.homeAbbrev}",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.sp
            )
            Text(
                listOfNotNull(subtitle, rememberPeriodText(score)).filter { it.isNotBlank() }.joinToString("  ·  "),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
            score.powerPlay?.let {
                Text(it.uppercase(), color = PenaltyColor, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
        SummaryScore(score.homeScore, Modifier.padding(end = 16.dp), size = 72)
        TeamLogo(score.homeLogo, 88.dp)
    }
}

// "1st · 4:28" - the period's clock counting down between checks while it
// runs - or just "1st INT", "Final".
@Composable
internal fun rememberPeriodText(score: NhlScore): String {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(score) {
        nowMs = System.currentTimeMillis()
        while (score.running) {
            delay(1000)
            nowMs = System.currentTimeMillis()
        }
    }
    val left = score.secondsLeftAt(nowMs)
    // The clock's run out but the NHL hasn't called it an intermission yet.
    if (left == 0 && !score.running && score.period.isNotBlank() && " " !in score.period &&
        !score.period.startsWith("Final") && score.period != "Pregame"
    ) return "End of ${score.period}"
    val showClock = score.period.isNotBlank() && !score.period.endsWith("INT") &&
        !score.period.startsWith("Final") && score.period != "Pregame"
    return listOfNotNull(score.period, "%d:%02d".format(left / 60, left % 60).takeIf { showClock })
        .filter { it.isNotBlank() }.joinToString(" · ")
}

@Composable
private fun GoalBody(goal: NhlCard.Goal) {
    // The scorer front and centre: a big headshot, name and stats.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Headshot(goal.scorer.headshotUrl, 210.dp)
        Spacer(Modifier.width(32.dp))
        Column(Modifier.weight(1f)) {
            Text(goal.scorer.name, color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    goal.scorer.number?.let { "#$it" },
                    "${ordinal(goal.seasonGoals)} goal of the season".takeIf { goal.seasonGoals > 0 },
                    goal.shotType,
                    goal.time
                ).joinToString(" · "),
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 21.sp
            )
            goal.stats?.let { stats ->
                Spacer(Modifier.height(18.dp))
                StatRow(
                    "GP" to stats.gamesPlayed.toString(),
                    "G" to stats.goals.toString(),
                    "A" to stats.assists.toString(),
                    "P" to stats.points.toString(),
                    "+/-" to (if (stats.plusMinus > 0) "+${stats.plusMinus}" else stats.plusMinus.toString()),
                    large = true
                )
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    if (goal.assists.isEmpty()) {
        Text("Unassisted", color = Color.White.copy(alpha = 0.6f), fontSize = 18.sp)
    } else {
        Text("ASSISTS", color = Color.White.copy(alpha = 0.45f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            goal.assists.forEach { assist ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Headshot(assist.player.headshotUrl, 48.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(assist.player.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${assist.seasonAssists} assist${if (assist.seasonAssists == 1) "" else "s"} this season",
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PenaltyBody(penalty: NhlCard.Penalty) {
    // The penalty clock counts down between checks while play's on.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(penalty.clock) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    val gameSeconds = penalty.clock.gameSecondsAt(nowMs)
    // Four at most, newest first - a line brawl would run off the screen.
    val calls = penalty.calls.take(4)
    val big = calls.size == 1
    val compact = calls.size > 2
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp)) {
        calls.forEach { call ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The penalized team's logo, then the player.
                val logo = when (call.teamAbbrev) {
                    penalty.score.homeAbbrev -> penalty.score.homeLogo
                    penalty.score.awayAbbrev -> penalty.score.awayLogo
                    else -> ""
                }
                if (logo.isNotBlank()) {
                    TeamLogo(logo, if (big) 110.dp else if (compact) 56.dp else 72.dp)
                    Spacer(Modifier.width(if (compact) 14.dp else 20.dp))
                }
                Headshot((call.player ?: call.servedBy)?.headshotUrl.orEmpty(), if (big) 150.dp else if (compact) 76.dp else 96.dp)
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        call.player?.name ?: "${call.teamAbbrev} bench",
                        color = Color.White,
                        fontSize = if (big) 32.sp else if (compact) 21.sp else 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        listOfNotNull(
                            call.teamAbbrev,
                            call.player?.number?.let { "#$it" },
                            call.time,
                            // Too many for the stat boxes - inline instead.
                            call.stats?.takeIf { compact }?.let { "${it.pim} PIM in ${it.gamesPlayed} GP" },
                            call.penaltiesTonight.takeIf { compact && it > 1 }?.let { "${ordinal(it)} tonight" }
                        ).joinToString(" · "),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 15.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        listOf(
                            call.call,
                            "${if (call.minutes > 0) "${call.minutes} min " else ""}${call.kind.lowercase()}"
                        ).filter { it.isNotBlank() }.joinToString(" · "),
                        color = PenaltyColor,
                        fontSize = if (big) 22.sp else if (compact) 16.sp else 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    val others = listOfNotNull(
                        call.servedBy?.takeIf { it.id != call.player?.id }?.let { "Served by ${it.name}" },
                        call.drawnBy?.let { "Drawn by ${it.name}" }
                    )
                    if (others.isNotEmpty()) {
                        Text(others.joinToString(" · "), color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp)
                    }
                    if (!compact && (call.stats != null || call.penaltiesTonight > 1)) {
                        Spacer(Modifier.height(10.dp))
                        StatRow(
                            *listOfNotNull(
                                call.stats?.let { "PIM" to it.pim.toString() },
                                call.stats?.let { "GP" to it.gamesPlayed.toString() },
                                ("Tonight" to call.penaltiesTonight.toString()).takeIf { call.penaltiesTonight > 1 }
                            ).toTypedArray()
                        )
                    }
                }
                call.endsAtGameSeconds?.let { endsAt ->
                    val left = (endsAt - gameSeconds).coerceAtLeast(0)
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 16.dp)) {
                        Text(
                            "%d:%02d".format(left / 60, left % 60),
                            color = Color.White,
                            fontSize = if (big) 40.sp else if (compact) 24.sp else 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text("LEFT", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp, letterSpacing = 2.sp)
                    }
                }
            }
        }
    }
}

@Composable
// large: the goal scorer's, to read from across the room.
private fun StatRow(vararg stats: Pair<String, String>, large: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(if (large) 14.dp else 10.dp)) {
        stats.forEach { (label, value) ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(if (large) 14.dp else 10.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .then(if (large) Modifier.widthIn(min = 76.dp) else Modifier)
                    .padding(horizontal = if (large) 20.dp else 14.dp, vertical = if (large) 10.dp else 6.dp)
            ) {
                Text(value, color = Color.White, fontSize = if (large) 34.sp else 20.sp, fontWeight = FontWeight.Bold)
                Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = if (large) 16.sp else 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Headshot(url: String, size: Dp) {
    AsyncImage(
        model = url.ifBlank { null },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.1f))
    )
}

private fun ordinal(n: Int): String {
    val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
    return "$n$suffix"
}

// At an intermission, the goals so far in order; at the end, each side's
// goals under its logo.
@Composable
private fun SummaryCard(summary: NhlCard.Summary) {
    Box(
        modifier = Modifier
            .widthIn(max = 940.dp)
            .padding(24.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(CardBackground)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    ) {
        Column(Modifier.padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 28.dp)) {
            // Each side's logo with its score beside it, big; the period
            // between them.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                TeamLogo(summary.away.logoUrl, 110.dp)
                SummaryScore(summary.away.score, Modifier.padding(start = 20.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text(
                        if (summary.final) summary.score.period.uppercase() else "${summary.period} intermission".uppercase(),
                        color = GoalColor,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 3.sp,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "${summary.away.abbrev}  ·  ${summary.home.abbrev}",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.sp
                    )
                    summary.score.powerPlay?.takeUnless { summary.final }?.let {
                        Text(it.uppercase(), color = PenaltyColor, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                }
                SummaryScore(summary.home.score, Modifier.padding(end = 20.dp))
                TeamLogo(summary.home.logoUrl, 110.dp)
            }
            Spacer(Modifier.height(20.dp))
            // A high-scoring game scrolls rather than running off the screen.
            val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
            Box(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState())) {
                when {
                    summary.goals.isEmpty() -> Text(
                        "No goals yet",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 20.sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        textAlign = TextAlign.Center
                    )
                    summary.final -> Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        listOf(summary.away, summary.home).forEach { team ->
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    team.name.uppercase().ifBlank { team.abbrev },
                                    color = Color.White.copy(alpha = 0.45f),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 2.sp
                                )
                                val goals = summary.goals.filter { it.teamAbbrev == team.abbrev }
                                if (goals.isEmpty()) {
                                    Text("No goals", color = Color.White.copy(alpha = 0.5f), fontSize = 16.sp)
                                }
                                goals.forEach { SummaryGoal(it, logoUrl = null) }
                            }
                        }
                    }
                    else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        summary.goals.forEach { goal ->
                            val logo = if (goal.teamAbbrev == summary.home.abbrev) summary.home.logoUrl else summary.away.logoUrl
                            SummaryGoal(goal, logoUrl = logo, showScore = true)
                        }
                    }
                }
            }
        }
    }
}

// A side's score beside its logo, big.
@Composable
private fun SummaryScore(goals: Int, modifier: Modifier = Modifier, size: Int = 88) {
    Text(goals.toString(), color = Color.White, fontSize = size.sp, fontWeight = FontWeight.Black, modifier = modifier)
}

// One goal in a summary: when, the scorer (their season total) and the
// assists - with the scoring team's logo and the score it made, in the
// intermission's single list.
@Composable
private fun SummaryGoal(goal: NhlGoalLine, logoUrl: String?, showScore: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (logoUrl != null) {
            TeamLogo(logoUrl, 40.dp)
            Spacer(Modifier.width(12.dp))
        }
        Headshot(goal.scorer.headshotUrl, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${goal.scorer.name} (${goal.seasonGoals})",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                listOfNotNull(
                    "${goal.period} ${goal.time}",
                    goal.strength,
                    if (goal.assists.isEmpty()) "Unassisted"
                    else goal.assists.joinToString(", ") { "${it.player.name} (${it.seasonAssists})" }
                ).joinToString(" · "),
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (showScore) {
            Text(
                "${goal.awayScore}–${goal.homeScore}",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }
}

// A team's logo - the NHL only has them as SVGs.
@Composable
internal fun TeamLogo(url: String, size: Dp) {
    val context = LocalContext.current
    val request = remember(url) {
        ImageRequest.Builder(context).data(url.ifBlank { null }).decoderFactory(SvgDecoder.Factory()).build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(size)
    )
}
