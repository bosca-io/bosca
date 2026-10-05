package bosca.ai.agents.git

import bosca.ai.agents.git.AgentRepoLayout.AGENTS_DIR
import bosca.ai.agents.git.AgentRepoLayout.MCP_SERVERS_DIR
import bosca.ai.agents.git.AgentRepoLayout.MD_EXTENSION
import bosca.ai.agents.git.AgentRepoLayout.PROMPTS_DIR
import bosca.ai.agents.git.AgentRepoLayout.RESOURCES_DIR
import bosca.ai.agents.git.AgentRepoLayout.TOOLS_DIR
import bosca.ai.agents.model.McpTransportType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException

/**
 * Outcome of parsing a single `.md` file from an AGENT_PROJECT repository. Either one
 * of the four typed file records, a `ParseError` with a human-readable message, or
 * `UnknownPath` for files outside the four known directories.
 */
sealed class ParsedFile {
    data class Agent(val file: AgentFile) : ParsedFile()
    data class Tool(val file: ToolFile) : ParsedFile()
    data class McpServer(val file: McpServerFile) : ParsedFile()
    data class Prompt(val file: PromptFile) : ParsedFile()
    data class Resource(val file: ResourceFile) : ParsedFile()
    data class ParseError(val path: String, val message: String) : ParsedFile()
    data class UnknownPath(val path: String) : ParsedFile()
}

/**
 * Parses a single `.md` file (frontmatter + body) into a typed [ParsedFile]. Pure — no
 * IO, no DB. Errors are returned as `ParseError` values rather than thrown so a batch
 * caller can collect all parse failures before deciding what to do.
 */
class AgentRepoFileParser {

    fun parse(path: String, content: String): ParsedFile {
        val directory = path.substringBefore('/', missingDelimiterValue = "")
        return when (directory) {
            AGENTS_DIR -> parseAgent(path, content)
            TOOLS_DIR -> parseTool(path, content)
            MCP_SERVERS_DIR -> parseMcpServer(path, content)
            PROMPTS_DIR -> parsePrompt(path, content)
            RESOURCES_DIR -> parseResource(path, content)
            else -> ParsedFile.UnknownPath(path)
        }
    }

    private fun parseAgent(path: String, content: String): ParsedFile {
        val (frontmatter, body) = splitFrontmatter(content).orError(path) { return it }
        val errors = mutableListOf<String>()
        val key = frontmatter.requireString("key", errors)
        val name = frontmatter.requireString("name", errors)
        val modelKey = frontmatter.requireString("model", errors)
        val promptKey = frontmatter.requireString("prompt", errors)
        val subAgents = frontmatter.optionalStringList("sub_agents", errors) ?: emptyList()
        val tools = frontmatter.optionalStringList("tools", errors) ?: emptyList()
        val configuration = frontmatter.optionalJsonElement("configuration")
        if (errors.isNotEmpty()) return ParsedFile.ParseError(path, errors.joinToString("; "))
        return ParsedFile.Agent(
            AgentFile(
                path = path,
                key = key!!,
                name = name!!,
                description = body.trim(),
                modelKey = modelKey!!,
                promptKey = promptKey!!,
                subAgentKeys = subAgents,
                toolKeys = tools,
                configuration = configuration
            )
        )
    }

    private fun parseTool(path: String, content: String): ParsedFile {
        val (frontmatter, body) = splitFrontmatter(content).orError(path) { return it }
        val errors = mutableListOf<String>()
        val key = frontmatter.requireString("key", errors)
        val name = frontmatter.requireString("name", errors)
        val mcpServer = frontmatter.optionalString("mcp_server", errors)
        val script = frontmatter.optionalString("script", errors)
        val graphqlOperation = frontmatter.optionalString("graphql_operation", errors)
        val graphqlInputTransform = frontmatter.optionalString("graphql_input_transform", errors)
        val graphqlOutputTransform = frontmatter.optionalString("graphql_output_transform", errors)
        val promptKey = frontmatter.optionalString("prompt", errors)
        val modelKey = frontmatter.optionalString("model", errors)
        val agentKey = frontmatter.optionalString("agent", errors)
        val configuration = frontmatter.optionalJsonElement("configuration")
        if (errors.isNotEmpty()) return ParsedFile.ParseError(path, errors.joinToString("; "))
        return ParsedFile.Tool(
            ToolFile(
                path = path,
                key = key!!,
                name = name!!,
                description = body.trim(),
                mcpServerKey = mcpServer,
                scriptKey = script,
                graphqlOperation = graphqlOperation,
                graphqlInputTransform = graphqlInputTransform,
                graphqlOutputTransform = graphqlOutputTransform,
                promptKey = promptKey,
                modelKey = modelKey,
                agentKey = agentKey,
                configuration = configuration
            )
        )
    }

    private fun parseResource(path: String, content: String): ParsedFile {
        val (frontmatter, body) = splitFrontmatter(content).orError(path) { return it }
        val errors = mutableListOf<String>()
        val key = frontmatter.requireString("key", errors)
        val name = frontmatter.requireString("name", errors)
        val staticText = frontmatter.optionalString("static_text", errors)
        val metadataSlug = frontmatter.optionalString("metadata", errors)
        val documentMetadataSlug = frontmatter.optionalString("document_metadata", errors)
        val documentVersion = frontmatter.optionalInt("document_version", errors)
        val contentMetadataSlug = frontmatter.optionalString("content_metadata", errors)
        val scriptKey = frontmatter.optionalString("script", errors)
        val graphqlOperation = frontmatter.optionalString("graphql_operation", errors)
        val graphqlInputTransform = frontmatter.optionalString("graphql_input_transform", errors)
        val graphqlOutputTransform = frontmatter.optionalString("graphql_output_transform", errors)
        val configuration = frontmatter.optionalJsonElement("configuration")
        if (errors.isNotEmpty()) return ParsedFile.ParseError(path, errors.joinToString("; "))
        return ParsedFile.Resource(
            ResourceFile(
                path = path,
                key = key!!,
                name = name!!,
                description = body.trim(),
                staticText = staticText,
                metadataSlug = metadataSlug,
                documentMetadataSlug = documentMetadataSlug,
                documentVersion = documentVersion,
                contentMetadataSlug = contentMetadataSlug,
                scriptKey = scriptKey,
                graphqlOperation = graphqlOperation,
                graphqlInputTransform = graphqlInputTransform,
                graphqlOutputTransform = graphqlOutputTransform,
                configuration = configuration
            )
        )
    }

    private fun parseMcpServer(path: String, content: String): ParsedFile {
        val (frontmatter, body) = splitFrontmatter(content).orError(path) { return it }
        val errors = mutableListOf<String>()
        val key = frontmatter.requireString("key", errors)
        val name = frontmatter.requireString("name", errors)
        val transportRaw = frontmatter.requireString("transport_type", errors)
        val transport = transportRaw?.let { runCatching { McpTransportType.valueOf(it) }.getOrNull() }
        if (transportRaw != null && transport == null) {
            errors += "invalid transport_type '$transportRaw' (expected one of ${McpTransportType.entries.joinToString(", ")})"
        }
        val configuration = frontmatter.optionalJsonElement("configuration") ?: JsonObject(emptyMap())
        val enabled = frontmatter.optionalBoolean("enabled", errors) ?: true
        if (errors.isNotEmpty()) return ParsedFile.ParseError(path, errors.joinToString("; "))
        return ParsedFile.McpServer(
            McpServerFile(
                path = path,
                key = key!!,
                name = name!!,
                description = body.trim(),
                transportType = transport!!,
                configuration = configuration,
                enabled = enabled
            )
        )
    }

    private fun parsePrompt(path: String, content: String): ParsedFile {
        val (frontmatter, body) = splitFrontmatter(content).orError(path) { return it }
        val errors = mutableListOf<String>()
        val key = frontmatter.requireString("key", errors)
        val name = frontmatter.requireString("name", errors)
        val description = frontmatter.requireString("description", errors)
        val inputType = frontmatter.requireString("input_type", errors)
        val outputType = frontmatter.requireString("output_type", errors)
        val schema = frontmatter.optionalJsonElement("schema")
        val sections = splitPromptBody(body)
        if (sections == null) errors += "prompt body must contain both '## System Prompt' and '## User Prompt' sections"
        if (errors.isNotEmpty()) return ParsedFile.ParseError(path, errors.joinToString("; "))
        return ParsedFile.Prompt(
            PromptFile(
                path = path,
                key = key!!,
                name = name!!,
                description = description!!,
                inputType = inputType!!,
                outputType = outputType!!,
                schema = schema,
                systemPrompt = sections!!.system,
                userPrompt = sections.user
            )
        )
    }

    private sealed class FrontmatterResult {
        data class Ok(val frontmatter: Map<String, Any?>, val body: String) : FrontmatterResult()
        data class Error(val message: String) : FrontmatterResult()
    }

    private inline fun FrontmatterResult.orError(path: String, onError: (ParsedFile) -> Nothing): Pair<Map<String, Any?>, String> =
        when (this) {
            is FrontmatterResult.Ok -> frontmatter to body
            is FrontmatterResult.Error -> onError(ParsedFile.ParseError(path, message))
        }

    private fun splitFrontmatter(content: String): FrontmatterResult {
        val normalized = content.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) {
            return FrontmatterResult.Error("file must start with YAML frontmatter delimited by '---' lines")
        }
        val afterOpen = normalized.removePrefix("---\n")
        val closeIdx = afterOpen.indexOf("\n---")
        if (closeIdx < 0) {
            return FrontmatterResult.Error("file's YAML frontmatter is not closed with a '---' line")
        }
        val yamlText = afterOpen.substring(0, closeIdx)
        val body = afterOpen.substring(closeIdx + "\n---".length).trimStart('\n')
        val map = try {
            @Suppress("UNCHECKED_CAST")
            (Yaml().load(yamlText) as? Map<String, Any?>) ?: emptyMap()
        } catch (e: YAMLException) {
            return FrontmatterResult.Error("malformed YAML frontmatter: ${e.message}")
        }
        return FrontmatterResult.Ok(map, body)
    }

    private data class PromptSections(val system: String, val user: String)

    private fun splitPromptBody(body: String): PromptSections? {
        val normalized = body.replace("\r\n", "\n")
        val systemHeader = "## System Prompt"
        val userHeader = "## User Prompt"
        val systemIdx = normalized.indexOf(systemHeader)
        val userIdx = normalized.indexOf(userHeader)
        if (systemIdx < 0 || userIdx < 0 || userIdx <= systemIdx) return null
        val systemText = normalized.substring(systemIdx + systemHeader.length, userIdx).trim()
        val userText = normalized.substring(userIdx + userHeader.length).trim()
        if (systemText.isEmpty() || userText.isEmpty()) return null
        return PromptSections(systemText, userText)
    }

    private fun Map<String, Any?>.requireString(key: String, errors: MutableList<String>): String? {
        val value = this[key]
        return when (value) {
            is String -> value
            null -> { errors += "missing required field '$key'"; null }
            else -> { errors += "field '$key' must be a string (got ${value::class.simpleName})"; null }
        }
    }

    private fun Map<String, Any?>.optionalString(key: String, errors: MutableList<String>): String? {
        val value = this[key] ?: return null
        return when (value) {
            is String -> value
            else -> { errors += "field '$key' must be a string (got ${value::class.simpleName})"; null }
        }
    }

    private fun Map<String, Any?>.optionalBoolean(key: String, errors: MutableList<String>): Boolean? {
        val value = this[key] ?: return null
        return when (value) {
            is Boolean -> value
            else -> { errors += "field '$key' must be a boolean (got ${value::class.simpleName})"; null }
        }
    }

    private fun Map<String, Any?>.optionalInt(key: String, errors: MutableList<String>): Int? {
        val value = this[key] ?: return null
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            else -> { errors += "field '$key' must be an integer (got ${value::class.simpleName})"; null }
        }
    }

    private fun Map<String, Any?>.optionalStringList(key: String, errors: MutableList<String>): List<String>? {
        val value = this[key] ?: return null
        if (value !is List<*>) {
            errors += "field '$key' must be a list (got ${value::class.simpleName})"
            return null
        }
        val result = mutableListOf<String>()
        for ((index, item) in value.withIndex()) {
            if (item !is String) {
                errors += "field '$key[$index]' must be a string (got ${item?.let { it::class.simpleName } ?: "null"})"
                return null
            }
            result += item
        }
        return result
    }

    private fun Map<String, Any?>.optionalJsonElement(key: String): JsonElement? {
        val value = this[key] ?: return null
        return toJsonElement(value)
    }

    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value.toDouble())
        is Number -> JsonPrimitive(value.toDouble())
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJsonElement(v) })
        is List<*> -> JsonArray(value.map { toJsonElement(it) })
        else -> JsonPrimitive(value.toString())
    }
}

/** Canonical path for an entity given its directory and key. */
fun agentRepoPath(directory: String, key: String): String = "$directory/$key$MD_EXTENSION"
