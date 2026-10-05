package bosca.content.recommendation.service

import bosca.service.Service
import kotlinx.serialization.json.JsonElement

/** Classifies content into the recommendation contexts cached on its persisted record. */
interface RecommendationContextClassifier : Service {

    /** Returns the context types assigned to metadata with the supplied content shape. */
    suspend fun classifyMetadata(contentType: String?, attributes: JsonElement?): List<String>

    /** Returns the context types assigned to a collection with the supplied content shape. */
    suspend fun classifyCollection(type: String, attributes: JsonElement?): List<String>

    /** Reclassifies all persisted metadata and collections from the current saved definitions. */
    suspend fun recompute()
}
