package bosca.pipelines.git

import bosca.pipelines.model.Pipeline
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
 * Renders a [Pipeline] plus its node/edge graph (the same [JsonElement] the editor consumes) to a
 * canonical YAML document. The inverse of [PipelineRepoFileParser]; both round-trip together when
 * fed the same pipeline.
 *
 * The polymorphic node graph is carried through as-is: each node map keeps its `type` discriminator
 * and node-specific fields, so the YAML stays human-editable without this codec knowing any
 * concrete node types.
 */
class PipelineRepoFileSerializer {

    private val yaml = Yaml(buildOptions())

    fun serialize(pipeline: Pipeline, graph: JsonElement): String {
        val graphYaml = jsonElementToYaml(graph) as? Map<*, *> ?: emptyMap<String, Any?>()
        val root = linkedMapOf<String, Any?>(
            "name" to pipeline.name,
            "accepted_input_type" to pipeline.acceptedInputType,
            "triggered" to pipeline.triggered,
        )
        if (pipeline.description.isNotEmpty()) root["description"] = pipeline.description
        // Tags ride the YAML so a repo sync round-trip preserves a pipeline's categorization.
        if (pipeline.tags.isNotEmpty()) root["tags"] = pipeline.tags
        // Endpoint exposure rides the YAML so a repo sync round-trip preserves it (the repo is the
        // source of truth for linked pipelines); omitted = not exposed.
        if (pipeline.key.isNotEmpty()) root["key"] = pipeline.key
        if (pipeline.api) root["api"] = true
        if (pipeline.public) root["public"] = true
        pipeline.schedule?.takeIf { it.isNotBlank() }?.let { root["schedule"] = it }
        root["nodes"] = graphYaml["nodes"] ?: emptyList<Any?>()
        root["edges"] = graphYaml["edges"] ?: emptyList<Any?>()
        return yaml.dump(root)
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
