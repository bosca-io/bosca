package bosca.recommendations.service

import bosca.recommendations.model.RecommendationPlacement
import bosca.recommendations.model.RecommendationPlacementInput
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Manages recommendation placements, which define named locations
 * within the application where recommendations are displayed. Each
 * placement is linked to one or more strategies that contribute
 * content suggestions for that location, with configurable item
 * limits and priority ordering.
 */
interface RecommendationPlacementService : Service {

    /**
     * Retrieves all recommendation placements.
     *
     * @return the list of all [RecommendationPlacement] instances
     */
    suspend fun getAll(): List<RecommendationPlacement>

    /**
     * Retrieves a single placement by its identifier.
     *
     * @param id the UUID of the placement
     * @return the [RecommendationPlacement] matching the given [id], or `null` if not found
     */
    suspend fun getById(id: UUID): RecommendationPlacement?

    /**
     * Retrieves a single placement by its URL-friendly slug.
     *
     * @param slug the unique slug identifying the placement
     * @return the [RecommendationPlacement] matching the given [slug], or `null` if not found
     */
    suspend fun getBySlug(slug: String): RecommendationPlacement?

    /**
     * Creates a new recommendation placement with optional strategy associations.
     *
     * @param input the placement definition
     * @param strategyIds the strategy UUIDs to associate with this placement
     * @return the newly created [RecommendationPlacement]
     */
    suspend fun add(input: RecommendationPlacementInput, strategyIds: List<UUID>): RecommendationPlacement

    /**
     * Updates an existing placement and replaces its strategy associations.
     *
     * @param id the UUID of the placement to update
     * @param input the updated placement definition
     * @param strategyIds the updated strategy UUIDs for this placement
     * @return the modified [RecommendationPlacement]
     */
    suspend fun edit(id: UUID, input: RecommendationPlacementInput, strategyIds: List<UUID>): RecommendationPlacement

    /**
     * Deletes a placement and its strategy associations.
     *
     * @param id the UUID of the placement to delete
     */
    suspend fun delete(id: UUID)

    /**
     * Retrieves the strategy UUIDs linked to a specific placement,
     * ordered by priority.
     *
     * @param placementId the UUID of the placement
     * @return the list of strategy UUIDs associated with the placement
     */
    suspend fun getStrategyIds(placementId: UUID): List<UUID>
}
