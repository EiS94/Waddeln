package com.schaubeck.waddeln.rules.protocol

import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Deal
import com.schaubeck.waddeln.rules.GameState
import com.schaubeck.waddeln.rules.PLAYER_COUNT
import com.schaubeck.waddeln.rules.Phase
import com.schaubeck.waddeln.rules.Waddeln
import com.schaubeck.waddeln.rules.agent.BotTalk
import com.schaubeck.waddeln.rules.agent.DiscardRule
import com.schaubeck.waddeln.rules.agent.Schadet
import com.schaubeck.waddeln.rules.agent.RuleBasedAgent
import com.schaubeck.waddeln.rules.ml.ActionCodec
import com.schaubeck.waddeln.rules.ml.FeatureEncoder
import com.schaubeck.waddeln.rules.nextPlayer
import com.schaubeck.waddeln.rules.observationFor
import com.schaubeck.waddeln.rules.partnerOf
import com.schaubeck.waddeln.rules.teamOf
import java.io.File
import kotlin.random.Random

/**
 * Writes random matches as JSON lines so the Python engine can replay them and check that it
 * computes the same deals, legal actions, trick winners, scores, bot decisions and features.
 *
 * Usage: `./gradlew :rules-core:generateProtocols` (see build.gradle.kts for arguments).
 */
object ProtocolGenerator {

    /** How actors are chosen: by seated agents (like the app) or uniformly over all players' actions. */
    enum class Mode { AGENTS, CHAOS }

    fun generateMatch(seed: Long, mode: Mode, withFeatures: Boolean): String {
        val random = Random(seed)
        val ruleSeats = List(PLAYER_COUNT) { random.nextBoolean() }
        val bot = RuleBasedAgent()
        var deal = Deal.random(random)
        var state = Waddeln.newMatch(random.nextInt(PLAYER_COUNT), deal)
        val rounds = mutableListOf<String>()
        var steps = mutableListOf<String>()
        var roundHeader = roundHeader(state, deal)

        while (true) {
            if (state.phase == Phase.ROUND_OVER || state.phase == Phase.MATCH_OVER) {
                rounds += roundJson(roundHeader, steps, state)
                if (state.phase == Phase.MATCH_OVER) break
                deal = Deal.random(random)
                state = Waddeln.startNextRound(state, deal)
                roundHeader = roundHeader(state, deal)
                steps = mutableListOf()
                continue
            }
            // Table talk as in BotTalk (seated agents) or around a random action (chaos)
            val (player, action, byBot) = when (mode) {
                Mode.AGENTS -> {
                    val actor = seatedActor(state)
                    state = talkHint(state, actor, steps)
                    val choose = { s: GameState ->
                        val allowed = Schadet.allowedActions(s, actor, Waddeln.legalActions(s, actor))
                        if (ruleSeats[actor]) bot.chooseAction(s.observationFor(actor), allowed) else allowed.random(random)
                    }
                    var chosen = choose(state)
                    if (BotTalk.asksSchadet(state, actor, chosen)) {
                        state = talkSchadet(state, actor, steps)
                        chosen = choose(state)
                    }
                    Triple(actor, chosen, ruleSeats[actor])
                }
                Mode.CHAOS -> {
                    val options = (0 until PLAYER_COUNT).flatMap { p -> Waddeln.legalActions(state, p).map { p to it } }
                    var (p, a) = options.random(random)
                    if (a == Action.Bleiben || a == Action.Gehen) state = talkHint(state, p, steps)
                    if (BotTalk.asksSchadet(state, p, a)) {
                        state = talkSchadet(state, p, steps)
                        a = Schadet.allowedActions(state, p, Waddeln.legalActions(state, p)).random(random)
                    }
                    Triple(p, a, false)
                }
            }
            val legal = List(PLAYER_COUNT) { Waddeln.legalActions(state, it) }
            val next = Waddeln.apply(state, player, action)
            steps += stepJson(state, next, player, action, legal, byBot, withFeatures)
            state = next
        }
        return Json.obj(
            "seed" to seed.toString(),
            "mode" to Json.str(mode.name),
            "rounds" to Json.arr(rounds),
            "finalScores" to Json.ints(state.scores),
            "winner" to Waddeln.matchWinner(state).toString(),
        )
    }

    /** [BotTalk.hintBeforeAnswer], written as a talk step if a hint was given. */
    private fun talkHint(state: GameState, actor: Int, steps: MutableList<String>): GameState {
        val next = BotTalk.hintBeforeAnswer(state, actor)
        next.hints.drop(state.hints.size).forEach { h ->
            steps += Json.obj("talk" to Json.str("hint"), "p" to h.player.toString(), "v" to h.hint.ordinal.toString())
        }
        return next
    }

    /** [BotTalk.askAndAnswerSchadet], written as two talk steps. */
    private fun talkSchadet(state: GameState, actor: Int, steps: MutableList<String>): GameState {
        val next = BotTalk.askAndAnswerSchadet(state, actor)
        steps += Json.obj("talk" to Json.str("ask"), "p" to actor.toString())
        steps += Json.obj("talk" to Json.str("answer"), "p" to partnerOf(actor).toString(), "v" to next.schadet!!.answer!!.ordinal.toString())
        return next
    }

    /** Same actor choice as [com.schaubeck.waddeln.rules.agent.MatchRunner]. */
    private fun seatedActor(state: GameState): Int {
        val toMove = state.playerToMove!!
        val pending = state.pendingSchub ?: return toMove
        var p = toMove
        while (teamOf(p) != pending.answeringTeam) p = nextPlayer(p)
        return p
    }

    private fun roundHeader(state: GameState, deal: Deal): List<Pair<String, String>> = listOf(
        "dealer" to state.dealer.toString(),
        "deck" to Json.ints(deal.deck.map { it.index }),
        "cut" to deal.cut.toString(),
        "scoresBefore" to Json.ints(state.scores),
        "hands" to Json.arr(state.hands.map { hand -> Json.ints(hand.map { it.index }) }),
        "stock" to Json.ints(state.stock.map { it.index }),
        "abheberKritten" to state.abheberKritten.toString(),
    )

    private fun roundJson(header: List<Pair<String, String>>, steps: List<String>, end: GameState): String {
        val result = end.result!!
        return Json.obj(
            *header.toTypedArray(),
            "steps" to Json.arr(steps),
            "result" to Json.obj(
                "winningTeam" to result.winningTeam.toString(),
                "points" to result.points.toString(),
                "reason" to Json.str(result.reason.name),
            ),
            "scoresAfter" to Json.ints(end.scores),
        )
    }

    private fun stepJson(
        before: GameState,
        after: GameState,
        player: Int,
        action: Action,
        legal: List<List<Action>>,
        byBot: Boolean,
        withFeatures: Boolean,
    ): String {
        val trickWinner = if (after.completedTricks.size > before.completedTricks.size) after.completedTricks.last().winner else -1
        val fields = mutableListOf(
            "p" to player.toString(),
            "a" to ActionCodec.encode(action).toString(),
            "bot" to byBot.toString(),
            "legal" to Json.arr(legal.map { acts -> Json.ints(acts.map(ActionCodec::encode)) }),
            "after" to Json.obj(
                "phase" to Json.str(after.phase.name),
                "scores" to Json.ints(after.scores),
                "gameValue" to after.gameValue.toString(),
                "tricksWon" to Json.ints(after.tricksWon),
                "trickWinner" to trickWinner.toString(),
                "pending" to (after.pendingSchub?.askingPlayer ?: -1).toString(),
                "lastSchubTeam" to (after.lastSchubTeam ?: -1).toString(),
                "laidOut" to (after.laidOut?.let { l -> Json.arr(l.map { set -> Json.ints(set.map { it.index }.sorted()) }) } ?: "null"),
            ),
        )
        if (action is Action.PlayCard) {
            // What the Abwurf-Regel makes of this card choice (checked against the Python port)
            val allowed = Schadet.allowedActions(before, player, legal[player])
            val ruled = DiscardRule.apply(before.observationFor(player), allowed, action)
            fields += "dr" to ActionCodec.encode(ruled).toString()
        }
        if (withFeatures) {
            val obs = before.observationFor(player)
            fields += "f" to Json.sparse(FeatureEncoder.encodeState(obs))
            fields += "af" to Json.sparse(FeatureEncoder.encodeAction(obs, action))
        }
        return Json.obj(*fields.toTypedArray())
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(args.getOrElse(0) { "protocols.jsonl" })
        val matches = args.getOrElse(1) { "300" }.toInt()
        val featureMatches = args.getOrElse(2) { "40" }.toInt()
        out.parentFile?.mkdirs()
        out.bufferedWriter().use { w ->
            for (i in 0 until matches) {
                val mode = if (i % 3 == 2) Mode.CHAOS else Mode.AGENTS
                w.write(generateMatch(seed = i.toLong(), mode = mode, withFeatures = i < featureMatches))
                w.newLine()
            }
        }
        println("Wrote $matches matches to ${out.absolutePath}")
    }
}

/** Minimal JSON building helpers (values are passed in already encoded). */
internal object Json {
    fun str(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    fun ints(values: List<Int>) = values.joinToString(",", "[", "]")
    fun arr(items: List<String>) = items.joinToString(",", "[", "]")
    fun obj(vararg fields: Pair<String, String>) = fields.joinToString(",", "{", "}") { (k, v) -> str(k) + ":" + v }

    /** Non-zero entries as [[index, value], ...]. */
    fun sparse(values: FloatArray) =
        values.withIndex().filter { it.value != 0f }.joinToString(",", "[", "]") { "[${it.index},${it.value}]" }
}
