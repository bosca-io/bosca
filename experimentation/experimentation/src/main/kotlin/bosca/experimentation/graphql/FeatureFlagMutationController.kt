package bosca.experimentation.graphql

import bosca.experimentation.model.FeatureFlag
import bosca.experimentation.model.FeatureFlagInput
import bosca.experimentation.model.FlagStatus
import bosca.experimentation.service.FeatureFlagService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object FeatureFlagsMutation

@TypeController
class FeatureFlagMutationController(
    private val featureFlagService: FeatureFlagService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<FeatureFlagsMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, flag: FeatureFlagInput): FeatureFlag {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return featureFlagService.add(flag)
    }

    @Field
    suspend fun edit(authentication: AuthenticationContext, id: UUID, flag: FeatureFlagInput): FeatureFlag {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return featureFlagService.edit(id, flag)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        featureFlagService.delete(id)
        return true
    }

    @Field
    suspend fun setStatus(authentication: AuthenticationContext, id: UUID, status: FlagStatus): FeatureFlag {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return featureFlagService.setStatus(id, status)
    }

    @Field
    suspend fun regenerateSalt(authentication: AuthenticationContext, id: UUID): FeatureFlag {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return featureFlagService.regenerateSalt(id)
    }
}
