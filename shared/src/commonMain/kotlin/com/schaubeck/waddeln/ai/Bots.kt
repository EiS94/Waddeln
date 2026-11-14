package com.schaubeck.waddeln.ai

import com.schaubeck.waddeln.game.Difficulty
import com.schaubeck.waddeln.game.OfflineGame
import com.schaubeck.waddeln.platform.Log
import com.schaubeck.waddeln.platform.ModelLoader
import com.schaubeck.waddeln.rules.PLAYER_COUNT
import com.schaubeck.waddeln.rules.agent.Agent
import com.schaubeck.waddeln.rules.agent.RuleBasedAgent
import com.schaubeck.waddeln.rules.agent.SchubManners
import com.schaubeck.waddeln.rules.ml.MlAgent
import com.schaubeck.waddeln.rules.ml.QFunction
import com.schaubeck.waddeln.rules.teamOf

/** Creates the bot players: ML bots if a model is bundled, otherwise the rule-based bot. */
class BotFactory(models: ModelLoader) : AutoCloseable {
    private val qFunction: QFunction? = try {
        models.load()
    } catch (e: Exception) {
        Log.w(TAG, "No usable model, using rule-based bots", e)
        null
    }

    /**
     * One agent per seat. The opponents play at [difficulty]; the partner always at full strength.
     * Without a model all bots are rule-based.
     */
    fun createBots(difficulty: Difficulty): List<Agent> = List(PLAYER_COUNT) { seat ->
        val q = qFunction
        val temperature = if (teamOf(seat) == teamOf(OfflineGame.HUMAN)) 0.0 else difficulty.temperature
        SchubManners(if (q == null || temperature == null) RuleBasedAgent() else MlAgent(q, temperature))
    }

    override fun close() {
        (qFunction as? AutoCloseable)?.close()
    }

    private companion object {
        const val TAG = "BotFactory"
    }
}
