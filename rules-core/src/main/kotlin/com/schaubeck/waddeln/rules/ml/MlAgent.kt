package com.schaubeck.waddeln.rules.ml

import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Observation
import com.schaubeck.waddeln.rules.agent.Agent
import com.schaubeck.waddeln.rules.agent.DiscardRule
import com.schaubeck.waddeln.rules.agent.LastTrick
import com.schaubeck.waddeln.rules.agent.RuleBasedAgent
import com.schaubeck.waddeln.rules.agent.SchubRule
import kotlin.math.exp
import kotlin.random.Random

/**
 * Evaluates network inputs (rows of [inputSize] floats) and returns one value per row: how much the
 * current game is expected to change the acting team's chance to win the match, scaled to the size
 * of game points (training/waddeln/match_value.py). Implemented with ONNX Runtime by the app/server.
 */
fun interface QFunction {
    fun evaluate(inputs: Array<FloatArray>): FloatArray

    /** [FeatureEncoder.INPUT_SIZE], or one of the older [FeatureEncoder.INPUT_SIZES] for an older model. */
    val inputSize: Int get() = FeatureEncoder.INPUT_SIZE
}

/**
 * Bot driven by a trained Deep Monte-Carlo network.
 *
 * [temperature] controls the difficulty: 0 always picks the best-rated action; higher values sample
 * from a softmax over the predicted values, so weaker moves are chosen more often.
 * If the network fails, the [fallback] decides.
 */
class MlAgent(
    private val q: QFunction,
    private val temperature: Double = 0.0,
    private val random: Random = Random.Default,
    private val fallback: Agent = RuleBasedAgent(),
) : Agent {

    override fun chooseAction(observation: Observation, legalActions: List<Action>): Action {
        require(legalActions.isNotEmpty())
        if (legalActions.size == 1 && legalActions.single() != Action.Schub) return legalActions.single()
        // With the partner's last card on the table the trick itself decides (as for the rule bot).
        if (Action.Bleiben in legalActions) {
            if (SchubRule.mustStay(observation)) return Action.Bleiben
            LastTrick.shouldStay(observation)?.let { return if (it) Action.Bleiben else Action.Gehen }
        }
        val values = try {
            val state = FeatureEncoder.encodeState(observation)
            q.evaluate(Array(legalActions.size) { FeatureEncoder.encodeFor(q.inputSize, state, FeatureEncoder.encodeAction(observation, legalActions[it])) })
        } catch (e: Exception) {
            return fallback.chooseAction(observation, legalActions)
        }
        return DiscardRule.apply(observation, legalActions, legalActions[pick(values)])
    }

    private fun pick(values: FloatArray): Int {
        if (temperature <= 0.0) return values.indices.maxBy { values[it] }
        val max = values.max()
        val weights = values.map { exp((it - max) / temperature) }
        var r = random.nextDouble() * weights.sum()
        for (i in weights.indices) {
            r -= weights[i]
            if (r <= 0) return i
        }
        return weights.lastIndex
    }
}
