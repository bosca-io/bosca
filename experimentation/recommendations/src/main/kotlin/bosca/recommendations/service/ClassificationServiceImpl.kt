package bosca.recommendations.service

import bosca.category.model.CategoryInput
import bosca.category.service.CategoryService
import bosca.content.metadata.service.MetadataAIService
import bosca.content.metadata.service.MetadataService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

/**
 * Classifies a content item via the AI topic matcher and records the result as **normalized content
 * categories on the item** (classify + features).
 *
 * The AI's topic matches (against the curated topic catalog) are a read-only signal; their names resolve
 * to canonical [bosca.category.model.Category] entities (find-or-create by name) which are assigned via
 * [MetadataService.setCategories]. Nothing is stored in the recommendations schema and the curated topic
 * collections are not modified — the recommend half reads the item's categories from content. Failures
 * surface (no silent catch); the recommendability pipeline records them.
 */
@ServiceImplementation
class ClassificationServiceImpl(
    private val metadataService: MetadataService,
    private val metadataAIService: MetadataAIService,
    private val categoryService: CategoryService,
) : ClassificationService {

    override suspend fun classify(metadataId: UUID): List<UUID> {
        val metadata = metadataService.getById(metadataId) ?: error("metadata $metadataId not found")
        val topics = metadataAIService.topics(metadata, document = null)
        if (topics.isEmpty()) return emptyList()

        val existingByName = categoryService.getAll().associateBy { it.name.lowercase() }
        val categoryIds = topics
            .map { topic -> existingByName[topic.name.lowercase()]?.id ?: categoryService.add(CategoryInput(name = topic.name)).id }
            .distinct()

        metadataService.setCategories(metadataId, categoryIds)
        return categoryIds
    }
}
