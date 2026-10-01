package bosca.experimentation.graphql

import bosca.experimentation.model.ExclusionLayer
import bosca.experimentation.model.Experiment
import bosca.experimentation.service.ExclusionLayerService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/**
 * Resolves fields on the ExclusionLayer GraphQL type, including the nested
 * experiments list.
 */
@TypeController(type = "ExclusionLayer")
class ExclusionLayerTypeController(
    private val exclusionLayerService: ExclusionLayerService
) : GraphQLController<ExclusionLayer> {

    @Field
    fun id(layer: ExclusionLayer): UUID = layer.id

    @Field
    fun name(layer: ExclusionLayer): String = layer.name

    @Field
    fun description(layer: ExclusionLayer): String = layer.description

    /**
     * Returns a page of experiments attached to this exclusion layer,
     * ordered by creation date descending. Pagination is pushed down to
     * the underlying SQL query; the server clamps `limit` to
     * [MAX_EXPERIMENTS_PAGE] and treats negative offsets as 0.
     */
    @Field
    suspend fun experiments(layer: ExclusionLayer, offset: Long, limit: Int): List<Experiment> {
        val safeOffset = maxOf(offset, 0L)
        val safeLimit = limit.coerceIn(1, MAX_EXPERIMENTS_PAGE)
        return exclusionLayerService.getExperiments(layer.id, safeOffset, safeLimit)
    }

    companion object {
        private const val MAX_EXPERIMENTS_PAGE = 100
    }

    @Field
    fun created(layer: ExclusionLayer): OffsetDateTime = layer.created
}
