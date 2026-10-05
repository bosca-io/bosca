package bosca.meilisearch.client.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Configuration for an embedding provider used in semantic and hybrid search.
 * The [source] identifies the provider type (e.g. "openAi", "ollama", "rest").
 */
@Serializable
data class EmbedderConfig(
    val source: String,
    val apiKey: String? = null,
    val model: String? = null,
    val dimensions: Int? = null,
    val documentTemplate: String? = null,
    val documentTemplateMaxBytes: Int? = null,
    val url: String? = null,
    val request: JsonElement? = null,
    val response: JsonElement? = null,
    val headers: JsonElement? = null,
)
