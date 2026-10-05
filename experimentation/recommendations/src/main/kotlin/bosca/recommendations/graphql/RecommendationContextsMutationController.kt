package bosca.recommendations.graphql

import bosca.artifacts.model.ArtifactAction
import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.recommendations.model.RecommendationContext
import bosca.recommendations.model.RecommendationContextInput
import bosca.recommendations.model.RecommendationContextModel
import bosca.recommendations.service.RecommendationContextService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.serialization.UUID

object RecommendationContextsMutation

/** Administrator context mutations and artifact-authorized training export reports. */
@TypeController
class RecommendationContextsMutationController(
    private val contextService: RecommendationContextService,
    private val groupEvaluator: GroupEvaluator,
    private val artifactPermissionEvaluator: ArtifactPermissionEvaluator,
) : GraphQLController<RecommendationContextsMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, context: RecommendationContextInput): RecommendationContext {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return contextService.add(context)
    }

    @Field
    suspend fun edit(authentication: AuthenticationContext, id: UUID, context: RecommendationContextInput): RecommendationContext {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return contextService.edit(id, context)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        contextService.delete(id)
        return true
    }

    @Field
    suspend fun recompute(authentication: AuthenticationContext): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        contextService.queueRecompute()
        return true
    }

    @Field
    suspend fun trainModel(authentication: AuthenticationContext, contextId: UUID): RecommendationContextModel {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return contextService.trainModel(contextId)
    }

    @Field
    suspend fun activateModel(authentication: AuthenticationContext, contextId: UUID, version: Long): RecommendationContextModel {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return contextService.activateModel(contextId, version)
    }

    @Field
    suspend fun pinModel(authentication: AuthenticationContext, contextId: UUID, version: Long, pinned: Boolean): RecommendationContextModel {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return contextService.pinModel(contextId, version, pinned)
    }

    @Field
    suspend fun modelExported(authentication: AuthenticationContext, version: Long, personalized: Boolean): Boolean {
        if (authentication.principal() !is ScopedAuthenticatedPrincipal || groupEvaluator.hasAdminGroup(authentication)) {
            groupEvaluator.verifyHasAdminGroup(authentication)
        } else {
            val model = contextService.getModel(version) ?: throw NoSuchElementException("Recommendation model not found")
            // The trainer may report only exports it can publish in the ML registry.
            artifactPermissionEvaluator.verify(authentication, "ml", "model", model.contentModelName, version.toString(), ArtifactAction.PUSH)
            if (personalized) {
                artifactPermissionEvaluator.verify(authentication, "ml", "model", model.personalizedModelName, version.toString(), ArtifactAction.PUSH)
            }
        }
        contextService.exportModel(version, personalized)
        return true
    }

    @Field
    suspend fun deleteModel(authentication: AuthenticationContext, contextId: UUID, version: Long): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        contextService.deleteModel(contextId, version)
        return true
    }
}
