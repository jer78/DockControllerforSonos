package io.github.jer78.dockcontroller

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// Experimental (Settings > Home Assistant > NHL Goals & Penalties): in TV
// mode, a "Panthers vs Senators"-style title is matched to one of today's
// NHL games, whose play-by-play is then followed (the NHL's unofficial API,
// api-web.nhle.com). Each new goal becomes an NhlCard, shown over TV mode
// (see NhlPlayCard) until the next goal or penalty, the goal being taken
// back, or the X; each new penalty shows every penalty still being served,
// until they've all ended. Each intermission brings up a summary of the
// goals so far, and the end of the game one of each side's goals.

data class NhlPlayer(
    val id: Long,
    val name: String,
    val number: Int?,
    val position: String,
    val headshotUrl: String,
    val teamAbbrev: String
)

// The player's numbers so far this season (regular season, or the playoffs
// during them). A goalie's goals, assists and +/- are just 0.
data class NhlSeasonStats(
    val gamesPlayed: Int,
    val goals: Int,
    val assists: Int,
    val points: Int,
    val plusMinus: Int,
    val pim: Int
)

// The game clock as of the last check: seconds of play since the opening
// face-off, and whether it was running then (so the card can count on).
data class NhlClock(val gameSeconds: Int, val running: Boolean, val atMs: Long) {
    fun gameSecondsAt(nowMs: Long): Int =
        if (running) gameSeconds + ((nowMs - atMs) / 1000).toInt() else gameSeconds
}

// The scoreboard as of the last check. period: "1st", "OT", "1st INT",
// "Pregame", "Final", "Final/OT".
// powerPlay: "OTT power play · 5 on 4", "FLA goalie pulled", or null - and
// powerPlaySecondsLeft, till the shorthanded side's next penalty ends.
// Logos are the NHL's for dark backgrounds (SVGs).
data class NhlScore(
    val awayAbbrev: String,
    val awayScore: Int,
    val homeAbbrev: String,
    val homeScore: Int,
    val period: String = "",
    val secondsLeft: Int = 0,
    val running: Boolean = false,
    val atMs: Long = 0L,
    val powerPlay: String? = null,
    val powerPlaySecondsLeft: Int? = null,
    val awayName: String = "",
    val homeName: String = "",
    val awayLogo: String = "",
    val homeLogo: String = ""
) {
    // The period's clock, counting down between checks while it runs.
    fun secondsLeftAt(nowMs: Long): Int =
        if (running) (secondsLeft - ((nowMs - atMs) / 1000).toInt()).coerceAtLeast(0) else secondsLeft

    fun powerPlaySecondsLeftAt(nowMs: Long): Int? =
        powerPlaySecondsLeft?.let { if (running) (it - ((nowMs - atMs) / 1000).toInt()).coerceAtLeast(0) else it }
}

data class NhlAssist(val player: NhlPlayer, val seasonAssists: Int)

// logoUrl: the NHL's logo for dark backgrounds (an SVG).
data class NhlTeam(val abbrev: String, val name: String, val logoUrl: String, val score: Int)

// One goal in a summary, with the score it made.
data class NhlGoalLine(
    val eventId: Int,
    val teamAbbrev: String,
    val scorer: NhlPlayer,
    val seasonGoals: Int,
    val assists: List<NhlAssist>,
    val strength: String?,
    val period: String,
    val time: String,
    val awayScore: Int,
    val homeScore: Int
)

data class NhlPenaltyCall(
    val eventId: Int,
    val teamAbbrev: String,
    // Null for a bench minor, which someone else serves.
    val player: NhlPlayer?,
    val servedBy: NhlPlayer?,
    val drawnBy: NhlPlayer?,
    // "High-sticking", and "Minor" / "Major" / ...
    val call: String,
    val kind: String,
    val minutes: Int,
    // When it ends, in game seconds - null when it doesn't run out (a
    // penalty shot, or one that ends the player's night).
    val endsAtGameSeconds: Int?,
    val stats: NhlSeasonStats?,
    // Including this one.
    val penaltiesTonight: Int,
    val time: String
)

sealed class NhlCard {
    abstract val key: String
    abstract val score: NhlScore

    data class Goal(
        val eventId: Int,
        val teamAbbrev: String,
        val scorer: NhlPlayer,
        val seasonGoals: Int,
        val stats: NhlSeasonStats?,
        val assists: List<NhlAssist>,
        // "Power play", "Shorthanded", "Empty net" - null at even strength.
        val strength: String?,
        val shotType: String?,
        val time: String,
        override val score: NhlScore
    ) : NhlCard() {
        override val key get() = "goal:$eventId"
    }

    // The goals so far, at an intermission (period: the one just played),
    // or all of them once it's over (final).
    data class Summary(
        val final: Boolean,
        val period: String,
        val away: NhlTeam,
        val home: NhlTeam,
        val goals: List<NhlGoalLine>,
        override val score: NhlScore
    ) : NhlCard() {
        override val key get() = if (final) "final" else "intermission:$period"
    }

    data class Penalty(
        val calls: List<NhlPenaltyCall>,
        val clock: NhlClock,
        override val score: NhlScore
    ) : NhlCard() {
        // One card for every penalty on the board, updated in place as
        // they're called and run out.
        override val key get() = "penalties"
    }
}

// fetch reads a URL as JSON (null for a "not found") - swapped in tests.
class NhlGameWatcher(
    private val scope: CoroutineScope,
    private val fetch: (String) -> JSONObject? = ::getJson
) {
    private val _card = MutableStateFlow<NhlCard?>(null)
    val card: StateFlow<NhlCard?> = _card.asStateFlow()

    // The followed game's scoreboard, kept up to date (null when no game's
    // followed) - shown under the artwork on Now Playing.
    private val _score = MutableStateFlow<NhlScore?>(null)
    val score: StateFlow<NhlScore?> = _score.asStateFlow()

    // A followed game is about to start or under way - TV mode's
    // screensaver holds off meanwhile.
    private val _gameOn = MutableStateFlow(false)
    val gameOn: StateFlow<Boolean> = _gameOn.asStateFlow()

    private var followingKey: String? = null
    private var job: Job? = null

    @Volatile private var teamNames: Map<String, List<String>> = emptyMap()
    @Volatile private var teamNamesAtMs = 0L

    private data class CachedStats(val stats: NhlSeasonStats?, val atMs: Long)
    private val statsCache = ConcurrentHashMap<String, CachedStats>()

    // How long each update is held before it's shown, so goals don't turn up
    // before the stream shows them.
    @Volatile private var delayMs = 0L

    // Called on every TV mode refresh with what Home Assistant reports, and
    // the delay setting. Only starts anything when the matchup changes.
    @Synchronized
    fun follow(texts: List<String>, delaySeconds: Int = 0) {
        delayMs = delaySeconds.coerceAtLeast(0) * 1000L
        val matchup = matchupIn(texts)
        if (matchup == null) {
            stop()
            return
        }
        val key = "${matchup.first}|${matchup.second}"
        if (key == followingKey) return
        stop()
        followingKey = key
        job = scope.launch(CoroutineExceptionHandler { _, e ->
            // Nothing the NHL sends should take the app down with it.
            DebugLog.e("NhlGames", "Stopped following the game", e)
        }) { followMatchup(matchup) }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        followingKey = null
        _card.value = null
        _score.value = null
        _gameOn.value = false
    }

    // The X: hidden until the next goal or penalty.
    fun dismiss() {
        _card.value = null
    }

    private suspend fun followMatchup(matchup: Pair<String, String>) {
        var gameId: Long? = null
        while (gameId == null) {
            gameId = try {
                findGame(matchup)
            } catch (e: Exception) {
                DebugLog.w("NhlGames", "Couldn't read today's games: ${e.message}")
                null
            }
            if (gameId == null) delay(FIND_RETRY_MS)
        }
        DebugLog.i("NhlGames", "Following game $gameId for ${matchup.first} vs ${matchup.second}")

        val followed = Followed()
        // Updates as they arrived, held for delayMs before they're shown.
        val held = ArrayDeque<Pair<Long, JSONObject>>()
        while (true) {
            try {
                fetch("https://api-web.nhle.com/v1/gamecenter/$gameId/play-by-play")
                    ?.let { held.addLast(System.currentTimeMillis() to it) }
            } catch (e: Exception) {
                DebugLog.w("NhlGames", "Couldn't read the play-by-play: ${e.message}")
            }
            // The newest update that's been held long enough.
            val cutoff = System.currentTimeMillis() - delayMs
            var ready: JSONObject? = null
            while (held.isNotEmpty() && held.first().first <= cutoff) ready = held.removeFirst().second
            if (ready != null) {
                // An update shaped in a way this doesn't expect is skipped,
                // rather than ending the game - or the app.
                val over = try {
                    onPlayByPlay(ready, followed)
                } catch (e: Exception) {
                    DebugLog.e("NhlGames", "Couldn't read an update for game $gameId", e)
                    false
                }
                if (over) {
                    DebugLog.i("NhlGames", "Game $gameId is over")
                    return
                }
            }
            val state = (ready ?: held.lastOrNull()?.second)?.optString("gameState")
            delay(if (state == null || state == "LIVE" || state == "CRIT") LIVE_POLL_MS else WAITING_POLL_MS)
        }
    }

    // One followed game's goals and penalties so far. The ones already in
    // the play-by-play when it was found aren't news - null until then.
    internal class Followed {
        var seen: MutableSet<Int>? = null
        // Whether it was between periods at the last check - null before
        // the first.
        var inIntermission: Boolean? = null
    }

    // Brings the card up to date with the game's latest play-by-play. True
    // once the game's over.
    internal fun onPlayByPlay(pbp: JSONObject, followed: Followed): Boolean {
        val plays = pbp.optJSONArray("plays").objects()
            .filter { it.optString("typeDescKey") in setOf("goal", "penalty") }
            .sortedBy { it.optInt("sortOrder") }
        val ids = plays.map { it.optInt("eventId") }
        val clock = clockOf(pbp)
        // The on-ice count only changes with the next play, so a power play
        // that's run out (or the period ending) would linger - it only
        // counts while a penalty's still being served.
        val score = withPowerPlay(pbp, plays, scoreOf(pbp), clock)

        val state = pbp.optString("gameState")
        val over = state == "OFF" || state == "FINAL"
        val intermission = pbp.optJSONObject("clock")?.optBoolean("inIntermission") == true
        val wasIntermission = followed.inIntermission
        followed.inIntermission = intermission

        val known = followed.seen
        if (known == null) {
            // Joining mid-game (or the app back after a restart): what's
            // past isn't news, but a goal just scored is, as are the
            // penalties still being served - or, between periods, the
            // summary.
            followed.seen = ids.toMutableSet()
            val latest = plays.lastOrNull()
            val justScored = latest?.optString("typeDescKey") == "goal" &&
                clock.gameSeconds - gameSecondsOf(latest) <= RECENT_GOAL_SECONDS
            _card.value = when {
                intermission -> summaryCard(pbp, plays, final = false, score)
                justScored -> goalCard(pbp, latest!!, score, previous = null)
                else -> penaltyCard(pbp, plays, score, clock, previous = emptyList())
            }
        } else {
            val fresh = plays.filter { it.optInt("eventId") !in known }
            known += ids
            if (fresh.isNotEmpty()) {
                val newest = fresh.last()
                _card.value = if (newest.optString("typeDescKey") == "goal") {
                    goalCard(pbp, newest, score, previous = null)
                } else {
                    penaltyCard(pbp, plays, score, clock, previous = emptyList())
                }
            } else {
                _card.update { card -> refreshed(card, pbp, plays, score, clock) }
            }
            when {
                // The game's over: every goal, by side. Stays till it's
                // closed or the show changes.
                over -> _card.value = summaryCard(pbp, plays, final = true, score)
                // A period's just ended: the goals so far, through the
                // intermission.
                intermission && wasIntermission == false -> _card.value = summaryCard(pbp, plays, final = false, score)
                // Play's back: the summary makes way for any penalties
                // carried over.
                !intermission && wasIntermission == true && _card.value is NhlCard.Summary ->
                    _card.value = penaltyCard(pbp, plays, score, clock, previous = emptyList())
            }
        }

        _score.value = score
        _gameOn.value = state in setOf("PRE", "LIVE", "CRIT")
        return over
    }

    // The card as it stands now, from the latest play-by-play - which the
    // NHL keeps correcting for a while (a penalty's call comes in after it's
    // first logged as just "minor", an assist gets added, more penalties
    // turn up at the same moment). A goal taken back (gone from the
    // play-by-play) comes down, and the penalty card once none are left.
    private fun refreshed(card: NhlCard?, pbp: JSONObject, plays: List<JSONObject>, score: NhlScore, clock: NhlClock): NhlCard? = when (card) {
        is NhlCard.Goal -> plays.firstOrNull { it.optInt("eventId") == card.eventId }
            ?.let { goalCard(pbp, it, score, previous = card) }
        is NhlCard.Penalty -> penaltyCard(pbp, plays, score, clock, previous = card.calls)
        is NhlCard.Summary -> summaryCard(pbp, plays, card.final, score)
        null -> null
    }

    // Every goal so far (not the shootout's), oldest first.
    private fun summaryCard(pbp: JSONObject, plays: List<JSONObject>, final: Boolean, score: NhlScore): NhlCard.Summary {
        val roster = rosterOf(pbp)
        fun team(key: String) = pbp.optJSONObject(key).let { t ->
            NhlTeam(
                abbrev = t?.optString("abbrev").orEmpty(),
                name = t?.optJSONObject("commonName")?.optString("default").orEmpty(),
                logoUrl = t?.optString("darkLogo").orEmpty().ifBlank { t?.optString("logo").orEmpty() },
                score = t?.optInt("score") ?: 0
            )
        }
        val goals = plays
            .filter { it.optString("typeDescKey") == "goal" && it.optJSONObject("periodDescriptor")?.optString("periodType") != "SO" }
            .mapNotNull { play ->
                val d = play.optJSONObject("details") ?: return@mapNotNull null
                val scorer = roster[d.optLong("scoringPlayerId")] ?: return@mapNotNull null
                NhlGoalLine(
                    eventId = play.optInt("eventId"),
                    teamAbbrev = teamAbbrev(pbp, d.optLong("eventOwnerTeamId")),
                    scorer = scorer,
                    seasonGoals = d.optInt("scoringPlayerTotal"),
                    assists = listOf(1, 2).mapNotNull { n ->
                        roster[d.optLong("assist${n}PlayerId")]?.let { NhlAssist(it, d.optInt("assist${n}PlayerTotal")) }
                    },
                    strength = strengthOf(pbp, play, d),
                    period = periodLabel(play.optJSONObject("periodDescriptor")),
                    time = play.optString("timeInPeriod").trimStart('0').ifBlank { "0:00" },
                    awayScore = d.optInt("awayScore"),
                    homeScore = d.optInt("homeScore")
                )
            }
        return NhlCard.Summary(
            final = final,
            period = periodLabel(pbp.optJSONObject("periodDescriptor")),
            away = team("awayTeam"),
            home = team("homeTeam"),
            goals = goals,
            score = score
        )
    }

    // Who's on the power play, from the penalties being served - the
    // play-by-play's on-ice count only changes with the play after a
    // penalty. Each side's minors, majors and bench minors still running
    // take a skater off (two at most); coincident ones cancel out. The
    // on-ice count still tells when a goalie's pulled.
    private fun withPowerPlay(pbp: JSONObject, plays: List<JSONObject>, score: NhlScore, clock: NhlClock): NhlScore {
        val board = penaltiesOnBoard(pbp, plays, clock.gameSeconds).filter { (play, endsAt) ->
            endsAt != null && play.optJSONObject("details")?.optString("typeCode") in setOf("MIN", "MAJ", "BEN")
        }
        fun ownedBy(team: String) = board.filter { (play, _) ->
            teamAbbrev(pbp, play.optJSONObject("details")?.optLong("eventOwnerTeamId") ?: 0) == team
        }
        val awayOff = ownedBy(score.awayAbbrev).size.coerceAtMost(2)
        val homeOff = ownedBy(score.homeAbbrev).size.coerceAtMost(2)
        val goaliePulled = score.powerPlay?.takeIf { it.endsWith("goalie pulled") }
        if (awayOff == homeOff) return score.copy(powerPlay = goaliePulled, powerPlaySecondsLeft = null)
        // Regular-season overtime is 3 on 3; a penalty there adds a skater
        // to the other side instead.
        val period = pbp.optJSONObject("periodDescriptor")
        val threeOnThree = period?.optString("periodType") == "OT" && pbp.optInt("gameType") != 3
        val (powerPlayTeam, shorthanded, extra) =
            if (awayOff > homeOff) Triple(score.homeAbbrev, score.awayAbbrev, awayOff - homeOff)
            else Triple(score.awayAbbrev, score.homeAbbrev, homeOff - awayOff)
        val (big, small) = if (threeOnThree) (3 + extra) to 3 else 5 to (5 - extra)
        val endsNext = ownedBy(shorthanded).mapNotNull { it.second }.min()
        return score.copy(
            powerPlay = "$powerPlayTeam power play · $big on $small",
            powerPlaySecondsLeft = (endsNext - clock.gameSeconds).coerceAtLeast(0)
        )
    }

    // Every penalty still being served, newest first - null when there are
    // none. previous: the calls already up, whose players' stats are kept.
    private fun penaltyCard(
        pbp: JSONObject,
        plays: List<JSONObject>,
        score: NhlScore,
        clock: NhlClock,
        previous: List<NhlPenaltyCall>
    ): NhlCard.Penalty? {
        val calls = penaltiesOnBoard(pbp, plays, clock.gameSeconds)
            .map { (play, endsAt) -> penaltyCall(pbp, plays, play, endsAt, previous) }
            .sortedByDescending { it.eventId.let { id -> plays.indexOfFirst { p -> p.optInt("eventId") == id } } }
        return if (calls.isEmpty()) null else NhlCard.Penalty(calls, clock, score)
    }

    // The penalties being served at `now` (game seconds), with when each
    // ends. A power-play goal ends the shorthanded team's minor that would
    // end first (the first half of a double minor). Ones that don't run out
    // (a game misconduct, a penalty shot) stay a couple of minutes, to be
    // seen.
    private fun penaltiesOnBoard(pbp: JSONObject, plays: List<JSONObject>, now: Int): List<Pair<JSONObject, Int?>> {
        val penalties = plays.filter { it.optString("typeDescKey") == "penalty" }
        val ends = penalties.associate { play ->
            val d = play.optJSONObject("details")
            val minutes = d?.optInt("duration") ?: 0
            val code = d?.optString("typeCode").orEmpty()
            play.optInt("eventId") to (if (minutes > 0 && code !in UNTIMED_PENALTIES) gameSecondsOf(play) + minutes * 60 else null)
        }.toMutableMap()
        plays.filter { it.optString("typeDescKey") == "goal" }.forEach { goal ->
            val details = goal.optJSONObject("details") ?: return@forEach
            if (strengthOf(pbp, goal, details) != "Power play") return@forEach
            val at = gameSecondsOf(goal)
            val scorers = details.optLong("eventOwnerTeamId")
            val ended = penalties
                .filter { p ->
                    val d = p.optJSONObject("details")
                    val end = ends[p.optInt("eventId")]
                    d?.optLong("eventOwnerTeamId") != scorers && d?.optString("typeCode") in setOf("MIN", "BEN") &&
                        end != null && gameSecondsOf(p) <= at && end > at
                }
                .minByOrNull { ends[it.optInt("eventId")]!! } ?: return@forEach
            val start = gameSecondsOf(ended)
            val doubleMinor = ended.optJSONObject("details")?.optInt("duration") == 4
            ends[ended.optInt("eventId")] = if (doubleMinor && at < start + 120) at + 120 else at
        }
        return penalties.mapNotNull { play ->
            val start = gameSecondsOf(play)
            val end = ends[play.optInt("eventId")]
            val on = if (end != null) start <= now && end > now else now < start + UNTIMED_SHOWN_SECONDS
            if (on) play to end else null
        }
    }

    // previous: the card already up for this goal, whose stats are kept
    // unless the goal's been given to someone else.
    private fun goalCard(pbp: JSONObject, play: JSONObject, score: NhlScore, previous: NhlCard.Goal?): NhlCard.Goal? {
        val roster = rosterOf(pbp)
        val details = play.optJSONObject("details") ?: return null
        val scorer = roster[details.optLong("scoringPlayerId")] ?: return null
        val seasonGoals = details.optInt("scoringPlayerTotal")
        // The player's page can lag a goal behind, so the goal's own count
        // wins.
        val stats = (if (previous?.scorer?.id == scorer.id) previous.stats else statsFor(scorer.id, pbp.optInt("gameType") == 3))
            ?.let { s ->
                val missing = (seasonGoals - s.goals).coerceAtLeast(0)
                s.copy(goals = s.goals + missing, points = s.points + missing)
            }
        val assists = listOf(1, 2).mapNotNull { n ->
            roster[details.optLong("assist${n}PlayerId")]?.let { NhlAssist(it, details.optInt("assist${n}PlayerTotal")) }
        }
        return NhlCard.Goal(
            eventId = play.optInt("eventId"),
            teamAbbrev = teamAbbrev(pbp, details.optLong("eventOwnerTeamId")),
            scorer = scorer,
            seasonGoals = seasonGoals,
            stats = stats,
            assists = assists,
            strength = strengthOf(pbp, play, details),
            shotType = details.optString("shotType").takeIf { it.isNotBlank() }
                ?.let { "${it.replace('-', ' ').replaceFirstChar(Char::uppercase)} shot" },
            time = whenOf(play),
            score = score
        )
    }

    private fun penaltyCall(
        pbp: JSONObject,
        plays: List<JSONObject>,
        play: JSONObject,
        endsAt: Int?,
        previous: List<NhlPenaltyCall>
    ): NhlPenaltyCall {
        val roster = rosterOf(pbp)
        val d = play.optJSONObject("details") ?: JSONObject()
        val player = roster[d.optLong("committedByPlayerId")]
        val code = d.optString("typeCode")
        val kind = PENALTY_KINDS[code] ?: code
        // Until the NHL fills in the call, it's just "minor" - shown as
        // only the kind ("2 min minor").
        val call = d.optString("descKey").replace('-', ' ').replaceFirstChar(Char::uppercase)
            .takeUnless { it.equals(kind, ignoreCase = true) || it.equals(code, ignoreCase = true) }
            .orEmpty()
        return NhlPenaltyCall(
            eventId = play.optInt("eventId"),
            teamAbbrev = teamAbbrev(pbp, d.optLong("eventOwnerTeamId")),
            player = player,
            servedBy = roster[d.optLong("servedByPlayerId")],
            drawnBy = roster[d.optLong("drawnByPlayerId")],
            call = call,
            kind = kind,
            minutes = d.optInt("duration"),
            endsAtGameSeconds = endsAt,
            stats = player?.let { p ->
                previous.firstOrNull { it.player?.id == p.id }?.stats ?: statsFor(p.id, pbp.optInt("gameType") == 3)
            },
            penaltiesTonight = player?.let { p ->
                plays.count { it.optString("typeDescKey") == "penalty" && it.optJSONObject("details")?.optLong("committedByPlayerId") == p.id }
            } ?: 0,
            time = whenOf(play)
        )
    }

    // "Power play", "Shorthanded" or "Empty net" from the situation code:
    // away goalie, away skaters, home skaters, home goalie (e.g. "1451").
    private fun strengthOf(pbp: JSONObject, play: JSONObject, details: JSONObject): String? {
        val code = play.optString("situationCode").takeIf { it.length == 4 && it.all(Char::isDigit) } ?: return null
        val home = details.optLong("eventOwnerTeamId") == pbp.optJSONObject("homeTeam")?.optLong("id")
        val ownSkaters = (if (home) code[2] else code[1]).digitToInt()
        val otherSkaters = (if (home) code[1] else code[2]).digitToInt()
        val otherGoalie = (if (home) code[0] else code[3]).digitToInt()
        return when {
            otherGoalie == 0 -> "Empty net"
            ownSkaters > otherSkaters -> "Power play"
            ownSkaters < otherSkaters -> "Shorthanded"
            else -> null
        }
    }

    private fun scoreOf(pbp: JSONObject): NhlScore {
        val away = pbp.optJSONObject("awayTeam")
        val home = pbp.optJSONObject("homeTeam")
        val clock = pbp.optJSONObject("clock")
        val period = periodLabel(pbp.optJSONObject("periodDescriptor"))
        val over = pbp.optString("gameState") in setOf("OFF", "FINAL")
        val notStarted = pbp.optString("gameState") in setOf("FUT", "PRE")
        return NhlScore(
            awayAbbrev = away?.optString("abbrev").orEmpty(),
            awayScore = away?.optInt("score") ?: 0,
            homeAbbrev = home?.optString("abbrev").orEmpty(),
            homeScore = home?.optInt("score") ?: 0,
            period = when {
                over -> if (period == "3rd") "Final" else "Final/$period"
                notStarted -> "Pregame"
                clock?.optBoolean("inIntermission") == true -> "$period INT"
                else -> period
            },
            secondsLeft = clock?.optInt("secondsRemaining") ?: 0,
            running = !over && clock?.optBoolean("running") == true,
            atMs = System.currentTimeMillis(),
            powerPlay = powerPlayOf(pbp, away?.optString("abbrev").orEmpty(), home?.optString("abbrev").orEmpty()),
            awayName = away?.optJSONObject("commonName")?.optString("default").orEmpty(),
            homeName = home?.optJSONObject("commonName")?.optString("default").orEmpty(),
            awayLogo = away?.optString("darkLogo").orEmpty().ifBlank { away?.optString("logo").orEmpty() },
            homeLogo = home?.optString("darkLogo").orEmpty().ifBlank { home?.optString("logo").orEmpty() }
        )
    }

    // Who's on the power play, from the latest play's situation code (away
    // goalie, away skaters, home skaters, home goalie): "OTT power play ·
    // 5 on 4", or "FLA goalie pulled".
    private fun powerPlayOf(pbp: JSONObject, away: String, home: String): String? {
        val latest = pbp.optJSONArray("plays").objects().maxByOrNull { it.optInt("sortOrder") } ?: return null
        val code = latest.optString("situationCode").takeIf { it.length == 4 && it.all(Char::isDigit) } ?: return null
        val (awayGoalie, awaySkaters, homeSkaters, homeGoalie) = code.map { it.digitToInt() }
        return when {
            awayGoalie == 0 -> "$away goalie pulled"
            homeGoalie == 0 -> "$home goalie pulled"
            homeSkaters > awaySkaters -> "$home power play · $homeSkaters on $awaySkaters"
            awaySkaters > homeSkaters -> "$away power play · $awaySkaters on $homeSkaters"
            else -> null
        }
    }

    private fun clockOf(pbp: JSONObject): NhlClock {
        val period = pbp.optJSONObject("periodDescriptor")
        val clock = pbp.optJSONObject("clock")
        val length = periodLengthSeconds(pbp.optInt("gameType"), period?.optString("periodType").orEmpty())
        val elapsed = length - (clock?.optInt("secondsRemaining", length) ?: length)
        return NhlClock(
            gameSeconds = periodStartSeconds(period?.optInt("number") ?: 1) + elapsed.coerceAtLeast(0),
            running = clock?.optBoolean("running") == true,
            atMs = System.currentTimeMillis()
        )
    }

    private fun gameSecondsOf(play: JSONObject): Int {
        val number = play.optJSONObject("periodDescriptor")?.optInt("number") ?: 1
        val (m, s) = play.optString("timeInPeriod").split(":").map { it.toIntOrNull() ?: 0 }.let { it.getOrElse(0) { 0 } to it.getOrElse(1) { 0 } }
        return periodStartSeconds(number) + m * 60 + s
    }

    // "1st · 13:49", "OT · 2:10", "2OT · 4:00".
    private fun whenOf(play: JSONObject): String =
        "${periodLabel(play.optJSONObject("periodDescriptor"))} · ${play.optString("timeInPeriod").trimStart('0').ifBlank { "0:00" }}"

    private fun periodLabel(period: JSONObject?): String {
        val number = period?.optInt("number") ?: 1
        return when (period?.optString("periodType")) {
            "OT" -> if (number > 4) "${number - 3}OT" else "OT"
            "SO" -> "SO"
            else -> when (number) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${number}th" }
        }
    }

    private fun rosterOf(pbp: JSONObject): Map<Long, NhlPlayer> {
        val abbrevs = listOfNotNull(pbp.optJSONObject("awayTeam"), pbp.optJSONObject("homeTeam"))
            .associate { it.optLong("id") to it.optString("abbrev") }
        return pbp.optJSONArray("rosterSpots").objects().associate { spot ->
            val id = spot.optLong("playerId")
            id to NhlPlayer(
                id = id,
                name = "${spot.optJSONObject("firstName")?.optString("default").orEmpty()} " +
                    spot.optJSONObject("lastName")?.optString("default").orEmpty(),
                number = spot.optInt("sweaterNumber", -1).takeIf { it >= 0 },
                position = spot.optString("positionCode"),
                headshotUrl = spot.optString("headshot"),
                teamAbbrev = abbrevs[spot.optLong("teamId")].orEmpty()
            )
        }
    }

    private fun teamAbbrev(pbp: JSONObject, teamId: Long): String =
        listOfNotNull(pbp.optJSONObject("awayTeam"), pbp.optJSONObject("homeTeam"))
            .firstOrNull { it.optLong("id") == teamId }?.optString("abbrev").orEmpty()

    // A player's season so far, from their page - kept a couple of minutes,
    // since a scrum can name the same player twice.
    private fun statsFor(playerId: Long, playoffs: Boolean): NhlSeasonStats? {
        val key = "$playerId|$playoffs"
        statsCache[key]?.takeIf { System.currentTimeMillis() - it.atMs < STATS_CACHE_MS }?.let { return it.stats }
        val stats = try {
            val featured = fetch("https://api-web.nhle.com/v1/player/$playerId/landing")?.optJSONObject("featuredStats")
            featured?.optJSONObject(if (playoffs) "playoffs" else "regularSeason")?.optJSONObject("subSeason")?.let {
                NhlSeasonStats(
                    gamesPlayed = it.optInt("gamesPlayed"),
                    goals = it.optInt("goals"),
                    assists = it.optInt("assists"),
                    points = it.optInt("points"),
                    plusMinus = it.optInt("plusMinus"),
                    pim = it.optInt("pim")
                )
            }
        } catch (e: Exception) {
            DebugLog.w("NhlGames", "Couldn't read player $playerId: ${e.message}")
            null
        }
        statsCache[key] = CachedStats(stats, System.currentTimeMillis())
        return stats
    }

    // Today's game (not over yet) between the two sides' teams, either way
    // round, matched by name, city or abbreviation.
    internal fun findGame(matchup: Pair<String, String>): Long? {
        val games = fetch("https://api-web.nhle.com/v1/score/now")?.optJSONArray("games").objects()
            .filter { it.optString("gameState") in setOf("FUT", "PRE", "LIVE", "CRIT") }
        if (games.isEmpty()) return null
        val names = currentTeamNames()
        fun namesOf(team: JSONObject?): List<String> {
            val abbrev = team?.optString("abbrev").orEmpty()
            return (names[abbrev].orEmpty() + team?.optJSONObject("name")?.optString("default").orEmpty() + abbrev)
                .map(::normalize).filter { it.isNotBlank() }.distinct()
        }
        fun matches(side: String, team: JSONObject?) = namesOf(team).any { " $side ".contains(" $it ") }
        val (a, b) = matchup
        return games.firstOrNull { game ->
            val away = game.optJSONObject("awayTeam")
            val home = game.optJSONObject("homeTeam")
            (matches(a, away) && matches(b, home)) || (matches(a, home) && matches(b, away))
        }?.optLong("id")
    }

    // Each team's full name, nickname and city (from the standings), by
    // abbreviation - read once a day. Without them, the nickname and
    // abbreviation from the scoreboard still match.
    private fun currentTeamNames(): Map<String, List<String>> {
        if (teamNames.isNotEmpty() && System.currentTimeMillis() - teamNamesAtMs < TEAM_NAMES_CACHE_MS) return teamNames
        try {
            val teams = fetch("https://api-web.nhle.com/v1/standings/now")?.optJSONArray("standings").objects()
            if (teams.isNotEmpty()) {
                teamNames = teams.associate { t ->
                    t.optJSONObject("teamAbbrev")?.optString("default").orEmpty() to listOf("teamName", "teamCommonName", "placeName")
                        .mapNotNull { t.optJSONObject(it)?.optString("default") }
                }
                teamNamesAtMs = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            DebugLog.w("NhlGames", "Couldn't read team names: ${e.message}")
        }
        return teamNames
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    companion object {
        // "Panthers vs Senators", "Florida Panthers vs. Ottawa Senators",
        // "FLA v OTT", "Panthers versus Senators".
        private val VS = Regex("""\s(?:vs\.?|v\.?|versus)\s""", RegexOption.IGNORE_CASE)

        // The two sides of the first "vs" in any of these, normalized.
        fun matchupIn(texts: List<String>): Pair<String, String>? {
            for (text in texts) {
                val match = VS.find(text) ?: continue
                val a = normalize(text.substring(0, match.range.first))
                val b = normalize(text.substring(match.range.last + 1))
                if (a.isNotBlank() && b.isNotBlank()) return a to b
            }
            return null
        }

        // Lowercase, without accents ("Montréal") or punctuation ("St. Louis").
        private fun normalize(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD)
                .replace(Regex("""\p{M}+"""), "")
                .lowercase()
                .replace(Regex("""[^a-z0-9]+"""), " ")
                .trim()

        private fun periodStartSeconds(number: Int) = (number - 1).coerceAtLeast(0) * 20 * 60

        // Regular-season overtime is five minutes; the rest are twenty.
        private fun periodLengthSeconds(gameType: Int, periodType: String) =
            if (periodType == "OT" && gameType != 3) 5 * 60 else 20 * 60

        // Penalties with no time to serve out: a game misconduct, a match
        // penalty, a penalty shot - shown for UNTIMED_SHOWN_SECONDS of play.
        private val UNTIMED_PENALTIES = setOf("GAM", "MAT", "PS")
        private const val UNTIMED_SHOWN_SECONDS = 2 * 60

        private val PENALTY_KINDS = mapOf(
            "MIN" to "Minor",
            "MAJ" to "Major",
            "MIS" to "Misconduct",
            "GAM" to "Game misconduct",
            "MAT" to "Match penalty",
            "BEN" to "Bench minor",
            "PS" to "Penalty shot"
        )

        // How recent (in game time) a goal still shows when the game's
        // joined - the clock's stopped for a while after one.
        private const val RECENT_GOAL_SECONDS = 90
        private const val LIVE_POLL_MS = 10_000L
        private const val WAITING_POLL_MS = 60_000L
        private const val FIND_RETRY_MS = 5 * 60_000L
        private const val STATS_CACHE_MS = 2 * 60_000L
        private const val TEAM_NAMES_CACHE_MS = 24 * 60 * 60_000L
    }
}

private val nhlClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

private fun getJson(url: String): JSONObject? {
    nhlClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) {
            DebugLog.w("NhlGames", "HTTP ${response.code} for $url")
            return null
        }
        return response.body?.string()?.let { JSONObject(it) }
    }
}
