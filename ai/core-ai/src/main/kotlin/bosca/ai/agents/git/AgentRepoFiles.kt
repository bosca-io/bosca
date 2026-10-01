package bosca.ai.agents.git

import bosca.ai.agents.model.McpTransportType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** A parsed agent file from an AGENT_PROJECT repository. References are by key. */
@Serializable
data class AgentFile(
    val path: String,
    val key: String,
    val name: String,
    val description: String,
    val modelKey: String,
    val promptKey: String,
    val subAgentKeys: List<String> = emptyList(),
    val toolKeys: List<String> = emptyList(),
    val configuration: JsonElement? = null
)

/**
 * A parsed agent-tool file. At most one implementation variant may be set: [mcpServerKey],
 * [scriptKey], [graphqlOperation], [promptKey]+[modelKey], or [agentKey] — or none, marking a
 * code-backed tool resolved by [key].
 */
@Serializable
data class ToolFile(
    val path: String,
    val key: String,
    val name: String,
    val description: String,
    val mcpServerKey: String? = null,
    val scriptKey: String? = null,
    val graphqlOperation: String? = null,
    val graphqlInputTransform: String? = null,
    val graphqlOutputTransform: String? = null,
    val promptKey: String? = null,
    val modelKey: String? = null,
    val agentKey: String? = null,
    val configuration: JsonElement? = null
)

/** A parsed MCP server registration file. */
@Serializable
data class McpServerFile(
    val path: String,
    val key: String,
    val name: String,
    val description: String,
    val transportType: McpTransportType,
    val configuration: JsonElement,
    val enabled: Boolean = true
)

/**
 * A parsed agent-resource file. Exactly one implementation variant must be set: [staticText],
 * [metadataSlug], [documentMetadataSlug] (+ optional [documentVersion]), [contentMetadataSlug],
 * [scriptKey], or [graphqlOperation] (+ optional transforms). Metadata references are carried as
 * human-readable slugs (resolved to UUIDs at sync time); the body is the resource description.
 */
@Serializable
data class ResourceFile(
    val path: String,
    val key: String,
    val name: String,
    val description: String,
    val staticText: String? = null,
    val metadataSlug: String? = null,
    val documentMetadataSlug: String? = null,
    val documentVersion: Int? = null,
    val contentMetadataSlug: String? = null,
    val scriptKey: String? = null,
    val graphqlOperation: String? = null,
    val graphqlInputTransform: String? = null,
    val graphqlOutputTransform: String? = null,
    val configuration: JsonElement? = null
)

/** A parsed prompt file. Body is split by `## System Prompt` / `## User Prompt` headers. */
@Serializable
data class PromptFile(
    val path: String,
    val key: String,
    val name: String,
    val description: String,
    val inputType: String,
    val outputType: String,
    val schema: JsonElement? = null,
    val systemPrompt: String,
    val userPrompt: String
)
