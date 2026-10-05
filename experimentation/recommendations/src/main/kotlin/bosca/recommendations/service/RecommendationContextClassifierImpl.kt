package bosca.recommendations.service

import bosca.content.collection.service.CollectionService
import bosca.content.metadata.service.MetadataService
import bosca.content.recommendation.service.RecommendationContextClassifier
import bosca.recommendations.model.RecommendationContext
import bosca.recommendations.repository.RecommendationContextRepository
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Applies saved recommendation context rules when content is persisted or reclassified. */
@ServiceImplementation
class RecommendationContextClassifierImpl(
    private val repository: RecommendationContextRepository,
    private val metadataService: MetadataService,
    private val collectionService: CollectionService,
) : RecommendationContextClassifier {

    override suspend fun classifyMetadata(contentType: String?, attributes: JsonElement?): List<String> =
        classifyMetadata(repository.getAll(), contentType, attributes)

    override suspend fun classifyCollection(type: String, attributes: JsonElement?): List<String> =
        classifyCollection(repository.getAll(), type, attributes)

    override suspend fun recompute() {
        val contexts = repository.getAll()
        var offset = 0L
        while (true) {
            val metadata = metadataService.getAll(offset, RECOMPUTE_PAGE_SIZE)
            for (item in metadata) {
                metadataService.setRecommendationContexts(
                    item.id,
                    classifyMetadata(contexts, item.contentType, item.attributes),
                )
            }
            if (metadata.size < RECOMPUTE_PAGE_SIZE) break
            offset += metadata.size
        }

        offset = 0L
        while (true) {
            val collections = collectionService.getAll(offset, RECOMPUTE_PAGE_SIZE)
            for (collection in collections) {
                collectionService.setRecommendationContexts(
                    collection.id,
                    classifyCollection(contexts, collection.type.name, collection.attributes),
                )
            }
            if (collections.size < RECOMPUTE_PAGE_SIZE) break
            offset += collections.size
        }
    }

    private fun classifyMetadata(
        contexts: List<RecommendationContext>,
        contentType: String?,
        attributes: JsonElement?,
    ): List<String> =
        contexts.asSequence()
            .filter { context ->
                context.contentFilter.metadata.matches(contentType, attributeType(attributes))
            }
            .map { it.type }
            .sorted()
            .toList()

    private fun classifyCollection(
        contexts: List<RecommendationContext>,
        type: String,
        attributes: JsonElement?,
    ): List<String> =
        contexts.asSequence()
            .filter { context ->
                val filter = context.contentFilter.collections ?: return@filter false
                filter.matches(type, attributeType(attributes))
            }
            .map { it.type }
            .sorted()
            .toList()

    private fun attributeType(attributes: JsonElement?): String? =
        ((attributes as? JsonObject)?.get("type") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull

    private companion object {
        const val RECOMPUTE_PAGE_SIZE = 500
    }
}
