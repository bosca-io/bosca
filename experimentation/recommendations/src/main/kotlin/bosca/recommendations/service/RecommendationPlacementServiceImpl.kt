package bosca.recommendations.service

import bosca.db.transaction
import bosca.recommendations.model.RecommendationPlacement
import bosca.recommendations.model.RecommendationPlacementInput
import bosca.recommendations.repository.RecommendationPlacementRepository
import bosca.recommendations.repository.RecommendationPlacementStrategyRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

/**
 * Orchestrates CRUD operations for recommendation placements and their strategy associations.
 * All mutating operations run within a database transaction to ensure the placement record
 * and its ordered strategy links are updated atomically, preventing inconsistent state when
 * strategies are re-prioritized or replaced.
 */
@ServiceImplementation
class RecommendationPlacementServiceImpl(
    private val placementRepository: RecommendationPlacementRepository,
    private val placementStrategyRepository: RecommendationPlacementStrategyRepository,
) : RecommendationPlacementService {

    override suspend fun getAll(): List<RecommendationPlacement> {
        return placementRepository.getAll()
    }

    override suspend fun getById(id: UUID): RecommendationPlacement? {
        return placementRepository.getById(id)
    }

    override suspend fun getBySlug(slug: String): RecommendationPlacement? {
        return placementRepository.getBySlug(slug)
    }

    override suspend fun add(input: RecommendationPlacementInput, strategyIds: List<UUID>): RecommendationPlacement = transaction {
        val placement = RecommendationPlacement(
            name = input.name,
            description = input.description,
            slug = input.slug,
            maxItems = input.maxItems,
            configuration = input.configuration,
        )
        val created = placementRepository.add(placement)
        strategyIds.forEachIndexed { index, strategyId ->
            placementStrategyRepository.add(created.id, strategyId, index)
        }
        created
    }

    override suspend fun edit(id: UUID, input: RecommendationPlacementInput, strategyIds: List<UUID>): RecommendationPlacement = transaction {
        val existing = placementRepository.getById(id)
            ?: throw NoSuchElementException("Placement not found: $id")
        val updated = existing.copy(
            name = input.name,
            description = input.description,
            slug = input.slug,
            maxItems = input.maxItems,
            configuration = input.configuration,
        )
        val result = placementRepository.update(updated)
        placementStrategyRepository.deleteByPlacementId(id)
        strategyIds.forEachIndexed { index, strategyId ->
            placementStrategyRepository.add(id, strategyId, index)
        }
        result
    }

    override suspend fun delete(id: UUID) = transaction {
        placementStrategyRepository.deleteByPlacementId(id)
        placementRepository.deleteById(id)
    }

    override suspend fun getStrategyIds(placementId: UUID): List<UUID> {
        return placementStrategyRepository.getStrategyIdsByPlacementId(placementId)
    }
}
