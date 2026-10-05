package bosca.ai.agents.git

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

/**
 * Renders a typed AGENT_PROJECT file record back to canonical Markdown-with-frontmatter
 * text. The inverse of [AgentRepoFileParser]; both round-trip together when fed the
 * same record.
 */
class AgentRepoFileSerializer {

    private val yaml = Yaml(buildOptions())

    fun serializeAgent(file: AgentFile): String {
        val frontmatter = linkedMapOf<String, Any?>(
            "key" to file.key,
            "name" to file.name,
            "model" to file.modelKey,
            "prompt" to file.promptKey,
        )
        if (file.subAgentKeys.isNotEmpty()) frontmatter["sub_agents"] = file.subAgentKeys
        if (file.toolKeys.isNotEmpty()) frontmatter["tools"] = file.toolKeys
        file.configuration?.let { frontmatter["configuration"] = jsonElementToYaml(it) }
        return buildFile(frontmatter, file.description)
    }

    fun serializeTool(file: ToolFile): String {
        val frontmatter = linkedMapOf<String, Any?>(
            "key" to file.key,
            "name" to file.name,
        )
        file.mcpServerKey?.let { frontmatter["mcp_server"] = it }
        file.scriptKey?.let { frontmatter["script"] = it }
        file.graphqlOperation?.let { frontmatter["graphql_operation"] = it }
        file.graphqlInputTransform?.let { frontmatter["graphql_input_transform"] = it }
        file.graphqlOutputTransform?.let { frontmatter["graphql_output_transform"] = it }
        file.promptKey?.let { frontmatter["prompt"] = it }
        file.modelKey?.let { frontmatter["model"] = it }
        file.agentKey?.let { frontmatter["agent"] = it }
        file.configuration?.let { frontmatter["configuration"] = jsonElementToYaml(it) }
        return buildFile(frontmatter, file.description)
    }

    fun serializeResource(file: ResourceFile): String {
        val frontmatter = linkedMapOf<String, Any?>(
            "key" to file.key,
            "name" to file.name,
        )
        file.staticText?.let { frontmatter["static_text"] = it }
        file.metadataSlug?.let { frontmatter["metadata"] = it }
        file.documentMetadataSlug?.let { frontmatter["document_metadata"] = it }
        file.documentVersion?.let { frontmatter["document_version"] = it }
        file.contentMetadataSlug?.let { frontmatter["content_metadata"] = it }
        file.scriptKey?.let { frontmatter["script"] = it }
        file.graphqlOperation?.let { frontmatter["graphql_operation"] = it }
        file.graphqlInputTransform?.let { frontmatter["graphql_input_transform"] = it }
        file.graphqlOutputTransform?.let { frontmatter["graphql_output_transform"] = it }
        file.configuration?.let { frontmatter["configuration"] = jsonElementToYaml(it) }
        return buildFile(frontmatter, file.description)
    }

    fun serializeMcpServer(file: McpServerFile): String {
        val frontmatter = linkedMapOf<String, Any?>(
            "key" to file.key,
            "name" to file.name,
            "transport_type" to file.transportType.name,
            "configuration" to jsonElementToYaml(file.configuration),
            "enabled" to file.enabled,
        )
        return buildFile(frontmatter, file.description)
    }

    fun serializePrompt(file: PromptFile): String {
        val frontmatter = linkedMapOf<String, Any?>(
            "key" to file.key,
            "name" to file.name,
            "description" to file.description,
            "input_type" to file.inputType,
            "output_type" to file.outputType,
        )
        file.schema?.let { frontmatter["schema"] = jsonElementToYaml(it) }
        val body = buildString {
            appendLine("## System Prompt")
            appendLine()
            appendLine(file.systemPrompt)
            appendLine()
            appendLine("## User Prompt")
            appendLine()
            appendLine(file.userPrompt)
        }.trimEnd() + "\n"
        return buildFile(frontmatter, body)
    }

    private fun buildFile(frontmatter: Map<String, Any?>, body: String): String {
        val sb = StringBuilder()
        sb.append("---\n")
        sb.append(yaml.dump(frontmatter))
        sb.append("---\n")
        if (body.isNotEmpty()) {
            sb.append("\n")
            sb.append(body)
            if (!body.endsWith("\n")) sb.append("\n")
        }
        return sb.toString()
    }

    private fun buildOptions(): DumperOptions {
        val opts = DumperOptions()
        opts.defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        opts.indent = 2
        opts.indicatorIndent = 0
        opts.isPrettyFlow = true
        opts.splitLines = false
        return opts
    }

    private fun jsonElementToYaml(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            element.isString -> element.content
            else -> element.booleanOrNull
                ?: element.longOrNull
                ?: element.doubleOrNull
                ?: element.content
        }
        is JsonObject -> linkedMapOf<String, Any?>().apply {
            element.forEach { (k, v) -> put(k, jsonElementToYaml(v)) }
        }
        is JsonArray -> element.map { jsonElementToYaml(it) }
    }
}
