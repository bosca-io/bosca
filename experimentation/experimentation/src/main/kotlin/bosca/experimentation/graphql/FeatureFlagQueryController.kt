package bosca.experimentation.graphql

import bosca.experimentation.model.FeatureFlag
import bosca.experimentation.model.FlagEvaluation
import bosca.experimentation.service.FeatureFlagService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object FeatureFlags

@TypeController
class FeatureFlagQueryController(
    private val featureFlagService: FeatureFlagService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<FeatureFlags> {

    @Field
    suspend fun all(authentication: AuthenticationContext, offset: Long, limit: Int): List<FeatureFlag> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return featureFlagService.getAll(maxOf(offset, 0), limit.coerceIn(1, 100))
    }

    @Field
    suspend fun flag(authentication: AuthenticationContext, id: UUID): FeatureFlag? {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return featureFlagService.getById(id)
    }

    @Field
    suspend fun flagByKey(authentication: AuthenticationContext, key: String): FeatureFlag? {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return featureFlagService.getByKey(key)
    }

    // evaluate and evaluateAll are intentionally open to all callers
    // (including unauthenticated) because client applications need flag
    // values without admin access. This means flag keys and resolved
    // values are visible to any caller — if a flag key or value is
    // sensitive it should not exist on the public schema at all.
    //
    // `experimentId`, however, is scrubbed for non-admin callers via
    // [scrubExperimentIdIfNonAdmin] / the explicit copy in [evaluateAll].
    // Returning it unconditionally would leak the existence and id of
    // every running experiment to anonymous traffic and let an attacker
    // probe the bucketing logic by rotating device attributes. The id
    // is only included for callers who pass
    // [GroupEvaluator.verifyHasAdminGroup].

    @Field
    suspend fun evaluate(
        authentication: AuthenticationContext,
        flagKey: String,
        installationId: String,
        device: AnalyticsDeviceInput?,
    ): FlagEvaluation {
        val principalId = authenticatedPrincipalId(authentication)
        val raw = featureFlagService.evaluate(
            flagKey, principalId, installationId, device?.toDevice()
        )
        return scrubExperimentIdIfNonAdmin(authentication, raw)
    }

    @Field
    suspend fun evaluateAll(
        authentication: AuthenticationContext,
        installationId: String,
        device: AnalyticsDeviceInput?,
    ): List<FlagEvaluation> {
        val principalId = authenticatedPrincipalId(authentication)
        val raw = featureFlagService.evaluateAll(
            principalId, installationId, device?.toDevice()
        )
        if (groupEvaluator.hasAdminGroup(authentication)) return raw
        return raw.map { it.copy(experimentId = null) }
    }

    private fun scrubExperimentIdIfNonAdmin(
        authentication: AuthenticationContext,
        evaluation: FlagEvaluation,
    ): FlagEvaluation {
        return if (groupEvaluator.hasAdminGroup(authentication)) evaluation
        else evaluation.copy(experimentId = null)
    }

    private fun authenticatedPrincipalId(authentication: AuthenticationContext): UUID? {
        val principal = authentication.principal() ?: return null
        return principal.id.takeUnless { it == UUID.NIL }
    }
}
