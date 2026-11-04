package bosca.system.ui

import bosca.system.ui.graphql.MeilisearchEmbedderInput
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull

@Serializable
data class MeilisearchEmbedderDraft(
    val name: String = "", val source: String = "", val model: String = "", val key: String = "",
    val dimensions: String = "", val documentTemplate: String = "", val documentTemplateMaxBytes: String = "",
    val url: String = "", val request: String = "null", val response: String = "null", val headers: String = "null",
) {
    fun input(): MeilisearchEmbedderInput {
        require(name.isNotBlank()) { "Name is required." }
        require(source in listOf("openAi", "huggingFace", "ollama", "rest", "userProvided")) { "Select an embedder source." }
        fun positive(value: String, label: String): Int? = value.takeIf(String::isNotBlank)?.let {
            requireNotNull(it.toIntOrNull()?.takeIf { number -> number > 0 }) { "$label must be a positive integer." }
        }
        fun json(value: String, label: String) = value.takeIf(String::isNotBlank)?.let {
            try { Json.parseToJsonElement(it).takeUnless { element -> element == JsonNull } }
            catch (failure: kotlinx.serialization.SerializationException) { throw IllegalArgumentException("$label must contain valid JSON.", failure) }
        }
        return MeilisearchEmbedderInput(name.trim(), source, model.takeIf { source != "userProvided" && it.isNotBlank() }, key.takeIf { source in listOf("openAi", "rest") && it.isNotBlank() },
            positive(dimensions, "Dimensions"), documentTemplate.takeIf(String::isNotBlank), positive(documentTemplateMaxBytes, "Template max bytes"),
            url.takeIf { source in listOf("rest", "ollama") && it.isNotBlank() },
            if (source == "rest") json(request, "Request mapping") else null,
            if (source == "rest") json(response, "Response mapping") else null,
            if (source == "rest") json(headers, "Headers") else null)
    }
}
