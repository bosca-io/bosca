package bosca.ai.agents.service

import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.ServerCall
import bosca.service.Service
import kotlinx.serialization.json.JsonObject

/**
 * SPI for running an [bosca.ai.agents.model.Agent] to completion and returning its reply.
 *
 * The capability lives in the `kit` implementation (Google ADK): `AgentBuilder` assembles the
 * agent's model, prompt, tools, and sub-agents into a runnable ADK agent and a one-shot `Runner`
 * executes it. This contract lets lean consumers — notably the `mcp/` bridge offering MCP-enabled
 * agents as callable tools, and the `agentId`-backed [bosca.ai.agents.model.AgentTool] variant —
 * invoke that existing engine without depending on the heavy `kit` module.
 *
 * [call] and [authentication] are threaded through so the agent's tools run as the calling
 * principal (the kit impl registers them with the ADK session, exactly as the chat path does).
 *
 * Resolve it optionally (via `ObjectProvider`): if no implementation is registered, agents are
 * still offered to MCP clients (listed and discoverable) but a call reports that execution is
 * unavailable rather than failing hard. This mirrors [bosca.ai.prompts.service.PromptModelExecutor].
 */
interface AgentExecutor : Service {
    /**
     * Run the agent identified by [agentId] with the caller-supplied [arguments] and return the
     * agent's final reply text.
     *
     * @param call the originating server call, so the agent's tools act on the caller's behalf
     * @param authentication the calling principal's authentication context
     * @param agentId the agent to run
     * @param arguments the caller-supplied arguments (typically the agent's input)
     * @return the agent's final reply text
     */
    suspend fun execute(
        call: ServerCall,
        authentication: AuthenticationContext,
        agentId: UUID,
        arguments: JsonObject
    ): String
}
