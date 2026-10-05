package bosca.ai.agents.model

import kotlinx.serialization.json.JsonElement

/**
 * Contract for an agent tool implementation that can be initialized and registered
 * within the agent system. Implementations provide specific tool capabilities
 * (e.g., web search, code execution) that agents can invoke during chat interactions.
 */
interface IAgentTool {
    /**
     * Unique identifier used to look up and match this tool implementation
     * against [AgentTool] records stored in the database.
     */
    val key: String

    /**
     * Performs any required setup for this tool, such as establishing connections
     * to external services or validating configuration parameters.
     *
     * @param configuration optional JSON configuration specific to this tool instance,
     *   as defined in the corresponding [AgentTool.configuration] field
     */
    suspend fun initialize(configuration: JsonElement? = null)
}
