package io.github.jer78.dockcontroller

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.sqrt

// Now Playing's artwork while an NHL game's followed (TV mode): the two
// teams side by side on their colours, split by a slanted seam - big logos,
// the score under each, and the period and its clock above. Sized from the
// artwork card, whatever its size. The power play goes underneath, in
// NhlPowerPlayPill.
@Composable
fun NhlMatchupArt(score: NhlScore) {
    val (awayColor, homeColor) = remember(score.awayAbbrev, score.homeAbbrev) {
        matchupColors(score.awayAbbrev, score.homeAbbrev)
    }
    val period = rememberPeriodText(score).replace(" INT", " Intermission")

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF0B0D10))) {
        val h = maxHeight
        val shadow = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 6f), 18f))

        Canvas(Modifier.fillMaxSize()) {
            val slant = size.width * 0.05f
            val top = size.width / 2 + slant
            val bottom = size.width / 2 - slant
            val left = Path().apply {
                moveTo(0f, 0f); lineTo(top, 0f); lineTo(bottom, size.height); lineTo(0f, size.height); close()
            }
            val right = Path().apply {
                moveTo(top, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height); lineTo(bottom, size.height); close()
            }
            drawPath(left, Brush.linearGradient(listOf(awayColor, lerp(awayColor, Color.Black, 0.45f)), Offset.Zero, Offset(top, size.height)))
            drawPath(right, Brush.linearGradient(listOf(lerp(homeColor, Color.Black, 0.45f), homeColor), Offset(bottom, 0f), Offset(size.width, size.height)))
            // A soft light behind each logo.
            listOf(0.25f, 0.75f).forEach { x ->
                val center = Offset(size.width * x, size.height * 0.4f)
                drawCircle(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent), center, size.height * 0.42f),
                    radius = size.height * 0.42f,
                    center = center
                )
            }
            // The seam.
            drawLine(Color.White.copy(alpha = 0.9f), Offset(top, 0f), Offset(bottom, size.height), strokeWidth = 3.dp.toPx())
            // Darker towards the bottom.
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)), startY = size.height * 0.55f))
        }

        Row(Modifier.fillMaxSize().padding(top = h * 0.1f, bottom = h * 0.06f)) {
            listOf(
                Triple(score.awayLogo, score.awayScore, score.awayName.ifBlank { score.awayAbbrev }),
                Triple(score.homeLogo, score.homeScore, score.homeName.ifBlank { score.homeAbbrev })
            ).forEach { (logo, goals, name) ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                ) {
                    TeamLogo(logo, h * 0.5f)
                    Text(
                        goals.toString(),
                        color = Color.White,
                        fontSize = (h.value * 0.17f).sp,
                        fontWeight = FontWeight.Black,
                        style = shadow
                    )
                    Text(
                        name.uppercase(),
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = (h.value * 0.045f).sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = shadow
                    )
                }
            }
        }

        if (period.isNotBlank()) {
            Text(
                period.uppercase(),
                color = Color.White,
                fontSize = (h.value * 0.05f).sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = h * 0.05f)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 5.dp)
            )
        }

    }
}

// Who's on the power play and its time left (counting down between checks
// while play's on), under the matchup on Now Playing.
@Composable
fun NhlPowerPlayPill(score: NhlScore, modifier: Modifier = Modifier) {
    val powerPlay = score.powerPlay ?: return
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(score) {
        nowMs = System.currentTimeMillis()
        while (score.running) {
            delay(1000)
            nowMs = System.currentTimeMillis()
        }
    }
    val left = score.powerPlaySecondsLeftAt(nowMs)
    Text(
        listOfNotNull(powerPlay, left?.let { "%d:%02d".format(it / 60, it % 60) }).joinToString(" · ").uppercase(),
        color = Color.Black,
        fontSize = 20.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.sp,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFFFF8A50))
            .padding(horizontal = 20.dp, vertical = 8.dp)
    )
}

// The two sides' colours - the away side's second colour when they'd look
// alike (Florida and Ottawa are both red).
private fun matchupColors(away: String, home: String): Pair<Color, Color> {
    val (awayPrimary, awaySecondary) = TEAM_COLORS[away] ?: (Color(0xFF2A2F36) to Color(0xFF14171B))
    val (homePrimary, _) = TEAM_COLORS[home] ?: (Color(0xFF3A4048) to Color(0xFF14171B))
    fun distance(a: Color, b: Color) =
        sqrt((a.red - b.red).let { it * it } + (a.green - b.green).let { it * it } + (a.blue - b.blue).let { it * it })
    return (if (distance(awayPrimary, homePrimary) < 0.3f) awaySecondary else awayPrimary) to homePrimary
}

// A team's main and second colour.
internal fun teamColors(abbrev: String): Pair<Color, Color> =
    TEAM_COLORS[abbrev] ?: (Color(0xFF3A4048) to Color(0xFF14171B))

// Each team's main and second colour.
private val TEAM_COLORS = mapOf(
    "ANA" to (Color(0xFFF47A38) to Color(0xFF111111)),
    "BOS" to (Color(0xFFFFB81C) to Color(0xFF111111)),
    "BUF" to (Color(0xFF003087) to Color(0xFFFFB81C)),
    "CGY" to (Color(0xFFC8102E) to Color(0xFFF1BE48)),
    "CAR" to (Color(0xFFCE1126) to Color(0xFF111111)),
    "CHI" to (Color(0xFFCF0A2C) to Color(0xFF111111)),
    "COL" to (Color(0xFF6F263D) to Color(0xFF236192)),
    "CBJ" to (Color(0xFF002654) to Color(0xFFCE1126)),
    "DAL" to (Color(0xFF006847) to Color(0xFF111111)),
    "DET" to (Color(0xFFCE1126) to Color(0xFF111111)),
    "EDM" to (Color(0xFF041E42) to Color(0xFFFF4C00)),
    "FLA" to (Color(0xFFC8102E) to Color(0xFF041E42)),
    "LAK" to (Color(0xFF2B2B2B) to Color(0xFFA2AAAD)),
    "MIN" to (Color(0xFF154734) to Color(0xFFA6192E)),
    "MTL" to (Color(0xFFAF1E2D) to Color(0xFF192168)),
    "NSH" to (Color(0xFFFFB81C) to Color(0xFF041E42)),
    "NJD" to (Color(0xFFCE1126) to Color(0xFF111111)),
    "NYI" to (Color(0xFF00539B) to Color(0xFFF47D30)),
    "NYR" to (Color(0xFF0038A8) to Color(0xFFCE1126)),
    "OTT" to (Color(0xFFC52032) to Color(0xFF111111)),
    "PHI" to (Color(0xFFF74902) to Color(0xFF111111)),
    "PIT" to (Color(0xFF1A1A1A) to Color(0xFFFCB514)),
    "SJS" to (Color(0xFF006D75) to Color(0xFF111111)),
    "SEA" to (Color(0xFF001628) to Color(0xFF68A2B9)),
    "STL" to (Color(0xFF002F87) to Color(0xFFFCB514)),
    "TBL" to (Color(0xFF002868) to Color(0xFF111111)),
    "TOR" to (Color(0xFF00205B) to Color(0xFF111111)),
    "UTA" to (Color(0xFF6CACE4) to Color(0xFF111111)),
    "VAN" to (Color(0xFF00205B) to Color(0xFF00843D)),
    "VGK" to (Color(0xFFB4975A) to Color(0xFF333F42)),
    "WSH" to (Color(0xFFC8102E) to Color(0xFF041E42)),
    "WPG" to (Color(0xFF041E42) to Color(0xFFAC162C))
)
