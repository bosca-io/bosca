package bosca.ai.agents.service

import bosca.ai.agents.model.McpServerRegistration
import bosca.service.Service
import kotlinx.serialization.json.JsonObject

/**
 * SPI for forwarding a tool call to an external (inbound) MCP server that Bosca has registered.
 *
 * Backs the `mcpServerId` [bosca.ai.agents.model.AgentTool] variant: an operator registers an
 * external MCP server ([McpServerRegistration]) and fronts one of its tools as a Bosca AgentTool;
 * a call to that AgentTool is forwarded here to the external server's `tools/call`.
 *
 * The connection machinery (transport, MCP client) lives in the `kit` implementation, so the lean
 * `mcp` module invokes it through this seam without depending on `kit`. This mirrors
 * [bosca.ai.prompts.service.PromptModelExecutor] and [AgentExecutor].
 *
 * Resolve it optionally (via `ObjectProvider`): if no implementation is registered, an
 * `mcpServerId`-backed tool is still offered to clients but a call reports dispatch is unavailable.
 */
interface McpServerToolExecutor : Service {
    /**
     * Call [toolName] on the external [server] with [arguments], returning the tool's text result.
     *
     * @param server the registered external MCP server to forward to
     * @param toolName the external tool's name (`tools/call` name on that server)
     * @param arguments the caller-supplied arguments
     * @return the external tool's result text
     */
    suspend fun callTool(server: McpServerRegistration, toolName: String, arguments: JsonObject): String
}
