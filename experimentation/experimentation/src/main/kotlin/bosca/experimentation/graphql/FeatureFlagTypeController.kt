package bosca.experimentation.graphql

import bosca.experimentation.model.Experiment
import bosca.experimentation.model.FeatureFlag
import bosca.experimentation.model.FlagStatus
import bosca.experimentation.model.FlagType
import bosca.experimentation.model.VariationAssignmentCount
import bosca.experimentation.service.ExperimentService
import bosca.experimentation.service.FeatureFlagService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

/**
 * Resolves fields on the FeatureFlag GraphQL type. Variations are exposed as a JSON
 * scalar so the client UI can manipulate them directly without needing a typed
 * GraphQL Variation type — the read shape and write shape stay in lockstep.
 */
@TypeController(type = "FeatureFlag")
class FeatureFlagTypeController(
    private val experimentService: ExperimentService,
    private val featureFlagService: FeatureFlagService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<FeatureFlag> {

    @Field
    fun id(flag: FeatureFlag): UUID = flag.id

    @Field
    fun key(flag: FeatureFlag): String = flag.key

    @Field
    fun name(flag: FeatureFlag): String = flag.name

    @Field
    fun description(flag: FeatureFlag): String = flag.description

    @Field
    fun type(flag: FeatureFlag): FlagType = flag.type

    @Field
    fun status(flag: FeatureFlag): FlagStatus = flag.status

    @Field
    fun variations(flag: FeatureFlag): JsonElement = flag.variations

    @Field
    fun defaultVariationKey(flag: FeatureFlag): String = flag.defaultVariationKey

    @Field
    fun targetingRules(flag: FeatureFlag): JsonElement? = flag.targetingRules

    @Field
    suspend fun salt(authentication: AuthenticationContext, flag: FeatureFlag): String {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return flag.salt
    }

    @Field
    fun created(flag: FeatureFlag): OffsetDateTime = flag.created

    @Field
    fun modified(flag: FeatureFlag): OffsetDateTime = flag.modified

    /**
     * Returns a page of experiments observing this flag's targeting
     * rules, ordered by creation date descending. Pagination is pushed
     * down to the underlying SQL query; the server clamps `limit` to
     * [MAX_EXPERIMENTS_PAGE] and treats negative offsets as 0.
     */
    @Field
    suspend fun experiments(flag: FeatureFlag, offset: Long, limit: Int): List<Experiment> {
        val safeOffset = maxOf(offset, 0L)
        val safeLimit = limit.coerceIn(1, MAX_EXPERIMENTS_PAGE)
        return experimentService.getByFlagId(flag.id, safeOffset, safeLimit)
    }

    companion object {
        private const val MAX_EXPERIMENTS_PAGE = 100
    }

    /** Returns the flag's current assignment distribution. */
    @Field
    suspend fun variationAssignments(
        flag: FeatureFlag,
    ): List<VariationAssignmentCount> = featureFlagService.getVariationAssignments(flag.id)
}
