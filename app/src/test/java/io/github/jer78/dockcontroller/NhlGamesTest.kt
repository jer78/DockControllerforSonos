package io.github.jer78.dockcontroller

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Real NHL API responses (trimmed), from San Jose at Montréal on 2026-10-20.
class NhlGamesTest {
    private fun resource(name: String) =
        JSONObject(javaClass.getResource("/nhl/$name")!!.readText())

    private val watcher = NhlGameWatcher(CoroutineScope(Dispatchers.Unconfined)) { url ->
        when {
            url.endsWith("/score/now") -> resource("score.json")
            url.endsWith("/standings/now") -> resource("standings.json")
            url.contains("/player/") -> resource("player.json")
            else -> null
        }
    }

    private val fullGame = resource("play_by_play.json")

    // The game as it stood live: only the plays up to lastEventId, with
    // the clock at period / elapsed (mm:ss into the period).
    private fun liveGame(lastEventId: Int, period: Int, elapsed: String, removed: Set<Int> = emptySet()): JSONObject {
        val game = JSONObject(fullGame.toString())
        val plays = JSONArray()
        // From the copy, so a test changing a play doesn't change fullGame.
        val all = game.getJSONArray("plays")
        val cut = (0 until all.length()).map { all.getJSONObject(it) }
            .first { it.getInt("eventId") == lastEventId }.getInt("sortOrder")
        for (i in 0 until all.length()) {
            val play = all.getJSONObject(i)
            if (play.getInt("sortOrder") <= cut && play.getInt("eventId") !in removed) plays.put(play)
        }
        val (m, s) = elapsed.split(":").map { it.toInt() }
        game.put("plays", plays)
        game.put("gameState", "LIVE")
        game.put("periodDescriptor", JSONObject().put("number", period).put("periodType", "REG"))
        game.put("clock", JSONObject().put("secondsRemaining", 1200 - (m * 60 + s)).put("running", true))
        return game
    }

    @Test
    fun readsMatchups() {
        assertEquals("sharks" to "canadiens", NhlGameWatcher.matchupIn(listOf("Sharks vs Canadiens")))
        assertEquals("nhl san jose sharks" to "montreal canadiens", NhlGameWatcher.matchupIn(listOf("NHL: San Jose Sharks vs. Montréal Canadiens")))
        assertEquals("sjs" to "mtl", NhlGameWatcher.matchupIn(listOf("Hockey Night", "SJS v MTL")))
        assertNull(NhlGameWatcher.matchupIn(listOf("Severance S2 E3", "Apple TV")))
    }

    @Test
    fun findsTodaysGameByNameCityOrAbbreviation() {
        for (title in listOf("Sharks vs Canadiens", "Montreal vs San Jose", "MTL vs. SJS", "San Jose Sharks versus Montréal Canadiens")) {
            val matchup = NhlGameWatcher.matchupIn(listOf(title))!!
            assertEquals(title, 2026020141L, watcher.findGame(matchup))
        }
        assertNull(watcher.findGame(NhlGameWatcher.matchupIn(listOf("Sharks vs Bruins"))!!))
    }

    @Test
    fun showsANewGoalWithItsAssists() {
        val followed = NhlGameWatcher.Followed()
        // What's already happened when the game's found isn't shown.
        watcher.onPlayByPlay(liveGame(277, 1, "15:50"), followed)
        assertNull(watcher.card.value)

        watcher.onPlayByPlay(liveGame(371, 1, "16:50"), followed)
        val goal = watcher.card.value as NhlCard.Goal
        assertEquals(8480018L, goal.scorer.id)
        assertEquals("MTL", goal.teamAbbrev)
        assertEquals(5, goal.seasonGoals)
        assertEquals(listOf(5, 7), goal.assists.map { it.seasonAssists })
        assertEquals("1st · 16:38", goal.time)
        assertTrue(goal.scorer.headshotUrl.endsWith(".png"))
        // The player's page lags behind; the goal's own count wins.
        assertEquals(5, goal.stats!!.goals)

        // Stays up with nothing new...
        watcher.onPlayByPlay(liveGame(371, 1, "17:10"), followed)
        assertNotNull(watcher.card.value)
        // ...until the goal's taken back.
        watcher.onPlayByPlay(liveGame(371, 1, "17:20", removed = setOf(371)), followed)
        assertNull(watcher.card.value)
    }

    @Test
    fun showsPenaltiesCalledTogetherUntilTheyEnd() {
        val followed = NhlGameWatcher.Followed()
        watcher.onPlayByPlay(liveGame(495, 2, "11:00"), followed)

        // Four roughing minors at 11:57 of the 2nd.
        watcher.onPlayByPlay(liveGame(630, 2, "12:30"), followed)
        val penalty = watcher.card.value as NhlCard.Penalty
        assertEquals(4, penalty.calls.size)
        assertTrue(penalty.calls.all { it.call == "Roughing" && it.kind == "Minor" && it.minutes == 2 })
        assertEquals(1200 + 11 * 60 + 57 + 120, penalty.calls.first().endsAtGameSeconds)

        watcher.onPlayByPlay(liveGame(630, 2, "13:50"), followed)
        assertNotNull(watcher.card.value)
        watcher.onPlayByPlay(liveGame(630, 2, "13:58"), followed)
        assertNull(watcher.card.value)
    }

    @Test
    fun latePenaltiesAndCallsFillInTheCard() {
        val followed = NhlGameWatcher.Followed()
        watcher.onPlayByPlay(liveGame(495, 2, "11:00"), followed)

        // First logged as just "minor", two of the four.
        val early = liveGame(624, 2, "12:00")
        val plays = early.getJSONArray("plays")
        for (i in 0 until plays.length()) {
            val play = plays.getJSONObject(i)
            if (play.getInt("eventId") in setOf(620, 624)) play.getJSONObject("details").put("descKey", "minor")
        }
        watcher.onPlayByPlay(early, followed)
        val first = watcher.card.value as NhlCard.Penalty
        assertEquals(2, first.calls.size)
        assertTrue(first.calls.all { it.call.isEmpty() && it.kind == "Minor" })

        // The next update has the calls, and the other two.
        watcher.onPlayByPlay(liveGame(630, 2, "12:10"), followed)
        val filled = watcher.card.value as NhlCard.Penalty
        assertEquals(listOf(630, 627, 624, 620), filled.calls.map { it.eventId })
        assertTrue(filled.calls.all { it.call == "Roughing" })
    }

    @Test
    fun joiningMidPenaltyShowsThePenaltiesStillOn() {
        // 391: San Jose holding at 17:22 of the 1st, so on till 19:22.
        val followed = NhlGameWatcher.Followed()
        watcher.onPlayByPlay(liveGame(391, 1, "18:00"), followed)
        val penalty = watcher.card.value as NhlCard.Penalty
        assertEquals(listOf(391), penalty.calls.map { it.eventId })
        assertTrue(watcher.gameOn.value)
    }

    @Test
    fun aPowerPlayGoalEndsTheMinor() {
        val game = liveGame(391, 1, "18:30")
        // Montréal (home) scores 5 on 4 at 18:00 of the 1st.
        val goal = JSONObject(game.getJSONArray("plays").let { plays ->
            (0 until plays.length()).map { plays.getJSONObject(it) }.first { it.getInt("eventId") == 371 }
        }.toString())
            .put("eventId", 999).put("sortOrder", 10_000).put("timeInPeriod", "18:00").put("situationCode", "1451")
        goal.getJSONObject("details").put("eventOwnerTeamId", 8)
        game.getJSONArray("plays").put(goal)
        watcher.onPlayByPlay(game, NhlGameWatcher.Followed())
        // The goal, just scored, rather than the minor it ended.
        assertEquals(999, (watcher.card.value as NhlCard.Goal).eventId)
    }

    @Test
    fun joiningJustAfterAGoalShowsIt() {
        // 371: Montréal at 16:38 of the 1st.
        watcher.onPlayByPlay(liveGame(371, 1, "17:00"), NhlGameWatcher.Followed())
        assertEquals(371, (watcher.card.value as NhlCard.Goal).eventId)
    }

    @Test
    fun theScoreboardHasThePeriodClockAndPowerPlay() {
        val followed = NhlGameWatcher.Followed()
        watcher.onPlayByPlay(liveGame(277, 1, "15:50"), followed)
        val game = liveGame(391, 1, "17:30")
        // Montréal 5 on 4 after the holding call.
        val plays = game.getJSONArray("plays")
        plays.getJSONObject(plays.length() - 1).put("situationCode", "1451")
        watcher.onPlayByPlay(game, followed)
        val score = watcher.card.value!!.score
        assertEquals("1st", score.period)
        assertEquals(150, score.secondsLeft)
        assertEquals("MTL power play · 5 on 4", score.powerPlay)
    }

    @Test
    fun aNewerPlayReplacesTheCard() {
        val followed = NhlGameWatcher.Followed()
        watcher.onPlayByPlay(liveGame(277, 1, "15:50"), followed)
        watcher.onPlayByPlay(liveGame(371, 1, "16:50"), followed)
        watcher.onPlayByPlay(liveGame(391, 1, "17:30"), followed)
        val penalty = watcher.card.value as NhlCard.Penalty
        assertEquals(1, penalty.calls.size)
        assertEquals(1, penalty.calls.single().penaltiesTonight.coerceAtLeast(1))
    }

    @Test
    fun theEndOfTheGameShowsEachSidesGoals() {
        val followed = NhlGameWatcher.Followed()
        watcher.onPlayByPlay(liveGame(277, 1, "15:50"), followed)
        watcher.onPlayByPlay(liveGame(391, 1, "17:30"), followed)
        val over = JSONObject(fullGame.toString())
        assertTrue(watcher.onPlayByPlay(over, followed))
        val summary = watcher.card.value as NhlCard.Summary
        assertTrue(summary.final)
        assertEquals("SJS" to "MTL", summary.away.abbrev to summary.home.abbrev)
        // Every goal in the play-by-play, each with its own side.
        val plays = fullGame.getJSONArray("plays")
        val expected = (0 until plays.length()).map { plays.getJSONObject(it) }
            .filter { it.getString("typeDescKey") == "goal" }
            .map { it.getInt("eventId") to (if (it.getJSONObject("details").getLong("eventOwnerTeamId") == 28L) "SJS" else "MTL") }
        assertEquals(expected, summary.goals.map { it.eventId to it.teamAbbrev })
        assertEquals("Final/OT", summary.score.period)
    }

    @Test
    fun anIntermissionShowsTheGoalsSoFar() {
        val followed = NhlGameWatcher.Followed()
        watcher.onPlayByPlay(liveGame(371, 1, "19:00"), followed)
        val intermission = liveGame(391, 1, "20:00")
        intermission.getJSONObject("clock").put("inIntermission", true).put("running", false)
        watcher.onPlayByPlay(intermission, followed)
        val summary = watcher.card.value as NhlCard.Summary
        assertEquals(false, summary.final)
        assertEquals("1st", summary.period)
        assertEquals(listOf(277, 371), summary.goals.map { it.eventId })
        assertEquals(listOf("1–0", "1–1"), summary.goals.map { "${it.awayScore}–${it.homeScore}" })
        assertEquals(listOf(5, 7), summary.goals[1].assists.map { it.seasonAssists })

        // Play's back: the holding minor from 17:22 is long over, so the
        // summary just comes down.
        watcher.onPlayByPlay(liveGame(391, 2, "0:10"), followed)
        assertNull(watcher.card.value)
    }

    @Test
    fun joiningBetweenPeriodsShowsTheSummary() {
        val intermission = liveGame(391, 1, "20:00")
        intermission.getJSONObject("clock").put("inIntermission", true)
        watcher.onPlayByPlay(intermission, NhlGameWatcher.Followed())
        assertTrue(watcher.card.value is NhlCard.Summary)
    }
}
