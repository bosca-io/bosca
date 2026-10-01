package bosca.meilisearch.client.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Paginated list of documents returned by the Meilisearch documents endpoint.
 */
@Serializable
data class DocumentsResponse(
    val results: List<JsonObject> = emptyList(),
    val total: Int = 0,
    val limit: Int = 20,
    val offset: Int = 0,
)
