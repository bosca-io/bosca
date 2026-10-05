package bosca.recommendations.graphql

import bosca.artifacts.model.ArtifactAction
import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.recommendations.model.RecommendationContext
import bosca.recommendations.model.RecommendationContextModel
import bosca.recommendations.service.RecommendationContextService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.serialization.UUID

object RecommendationContexts

/** Administrator context reads and artifact-filtered model discovery for registry clients. */
@TypeController
class RecommendationContextsController(
    private val contextService: RecommendationContextService,
    private val groupEvaluator: GroupEvaluator,
    private val artifactPermissionEvaluator: ArtifactPermissionEvaluator,
) : GraphQLController<RecommendationContexts> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<RecommendationContext> {
        if (isArtifactToken(authentication)) {
            return contextService.getAll().filter { context ->
                contextService.getModels(context.id).any { canPullModel(authentication, it) }
            }
        }
        groupEvaluator.verifyHasAdminGroup(authentication)
        return contextService.getAll()
    }

    @Field
    suspend fun models(authentication: AuthenticationContext, contextId: UUID): List<RecommendationContextModel> {
        if (isArtifactToken(authentication)) {
            return contextService.getModels(contextId).filter { canPullModel(authentication, it) }
        }
        groupEvaluator.verifyHasAdminGroup(authentication)
        return contextService.getModels(contextId)
    }

    @Field
    suspend fun servingModels(authentication: AuthenticationContext): List<RecommendationContextModel> {
        if (isArtifactToken(authentication)) {
            return contextService.getServingModels().filter { canPullModel(authentication, it) }
        }
        groupEvaluator.verifyHasAdminGroup(authentication)
        return contextService.getServingModels()
    }

    @Field
    suspend fun context(authentication: AuthenticationContext, id: UUID): RecommendationContext? {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return contextService.getById(id)
    }

    @Field
    suspend fun contextByType(authentication: AuthenticationContext, type: String): RecommendationContext? {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return contextService.getByType(type)
    }

    private fun isArtifactToken(authentication: AuthenticationContext): Boolean =
        authentication.principal() is ScopedAuthenticatedPrincipal && !groupEvaluator.hasAdminGroup(authentication)

    private suspend fun canPullModel(authentication: AuthenticationContext, model: RecommendationContextModel): Boolean =
        artifactPermissionEvaluator.evaluate(authentication, "ml", "model", model.contentModelName, model.version.toString(), ArtifactAction.PULL) &&
            (!model.personalized || artifactPermissionEvaluator.evaluate(
                authentication, "ml", "model", model.personalizedModelName, model.version.toString(), ArtifactAction.PULL,
            ))
}
