package bosca.recommendations.service

import bosca.recommendations.model.RecommendationStrategy
import bosca.recommendations.model.RecommendationStrategyInput
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Manages recommendation strategy definitions and their lifecycle.
 * Strategies define the algorithm and configuration used for candidate
 * generation. Materialized strategies (trending, co-engagement) are bound
 * to an analytics query and refreshed on a schedule; the ML model strategy is
 * served live per request. Personalization is applied at read time, so
 * strategies carry no per-profile targeting.
 */
interface RecommendationStrategyService : Service {

    /**
     * Retrieves a paginated list of all recommendation strategies.
     *
     * @param offset the number of strategies to skip for pagination
     * @param limit the maximum number of strategies to return
     * @return the list of [RecommendationStrategy] instances
     */
    suspend fun getAll(offset: Long, limit: Int): List<RecommendationStrategy>

    /**
     * Retrieves a single strategy by its identifier.
     *
     * @param id the UUID of the strategy
     * @return the [RecommendationStrategy] matching the given [id], or `null` if not found
     */
    suspend fun getById(id: UUID): RecommendationStrategy?

    /**
     * Retrieves multiple strategies by their identifiers in a single query.
     *
     * @param ids the UUIDs of the strategies to retrieve
     * @return the list of [RecommendationStrategy] instances found
     */
    suspend fun getByIds(ids: List<UUID>): List<RecommendationStrategy>

    /**
     * Creates a new recommendation strategy from the provided specification,
     * including scheduled evaluation setup for materialized strategies.
     *
     * @param input the strategy definition
     * @return the newly created [RecommendationStrategy]
     */
    suspend fun add(input: RecommendationStrategyInput): RecommendationStrategy

    /**
     * Updates an existing strategy with the provided specification,
     * reconfiguring scheduled evaluation.
     *
     * @param id the UUID of the strategy to update
     * @param input the updated strategy definition
     * @return the modified [RecommendationStrategy]
     */
    suspend fun edit(id: UUID, input: RecommendationStrategyInput): RecommendationStrategy

    /**
     * Deletes a strategy and all recommendations it has generated.
     *
     * @param id the UUID of the strategy to delete
     */
    suspend fun delete(id: UUID)

    /**
     * Triggers evaluation of a materialized strategy, executing its analytics
     * query and refreshing its global candidate pool. The ML model strategy is
     * served live per request, so evaluating it is a no-op.
     *
     * @param strategyId the UUID of the strategy to evaluate
     * @return the updated [RecommendationStrategy] with refreshed evaluation timestamp
     */
    suspend fun evaluate(strategyId: UUID): RecommendationStrategy
}
