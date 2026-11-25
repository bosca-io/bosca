package bosca.ai.kit.harness.acp

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.PromptResponse

/**
 * Runs one remote turn through Koog without a local LLM or planning loop. Durable ownership and
 * reconciliation precede this strategy: neither a failed prompt nor a restored graph is retry proof.
 * Cancellation is driven by the owner through ACP session/cancel while this collector drains events.
 */
fun kitAcpStrategy(): AIAgentGraphStrategy<KitAcpTurn, PromptResponse> = strategy("kit_acp_turn") {
    val remoteTurn by node<KitAcpTurn, PromptResponse> { turn ->
        val capabilities = turn.session.client.agentInfo.capabilities.promptCapabilities
        require(turn.content.isNotEmpty()) { "ACP prompt must contain content" }
        turn.content.forEach { content ->
            when (content) {
                is ContentBlock.Image -> require(capabilities.image) { "Runtime does not support image input" }
                is ContentBlock.Audio -> require(capabilities.audio) { "Runtime does not support audio input" }
                is ContentBlock.Resource -> require(capabilities.embeddedContext) { "Runtime does not support embedded context" }
                is ContentBlock.Text, is ContentBlock.ResourceLink -> Unit
            }
        }
        var terminal: PromptResponse? = null
        turn.connection.prompt(turn.session, turn.content, turn.correlation).collect { event ->
            check(terminal == null) { "ACP emitted an event after its terminal response" }
            turn.onEvent(event)
            if (event is Event.PromptResponseEvent) terminal = event.response
        }
        checkNotNull(terminal) { "ACP disconnected without a terminal turn response" }
    }
    nodeStart then remoteTurn then nodeFinish
}
