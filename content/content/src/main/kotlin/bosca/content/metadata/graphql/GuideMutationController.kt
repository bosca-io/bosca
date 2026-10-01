package bosca.content.metadata.graphql

import bosca.content.metadata.model.GuideType
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.GuideService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext

class GuideMutation(
    val metadata: Metadata
)

@TypeController
class GuideMutationController(
    private val guideService: GuideService,
    private val permissionEvaluator: MetadataPermissionEvaluator,
) : GraphQLController<GuideMutation> {

    @Field
    suspend fun setType(
        authorization: AuthenticationContext,
        mutation: GuideMutation,
        guideType: GuideType,
    ): Boolean {
        permissionEvaluator.verifyAllowed(authorization, mutation.metadata, PermissionAction.EDIT)
        guideService.setGuideType(mutation.metadata.id, mutation.metadata.version, guideType)
        return true
    }

    @Field
    suspend fun setRrule(
        authorization: AuthenticationContext,
        mutation: GuideMutation,
        rrule: String,
    ): Boolean {
        permissionEvaluator.verifyAllowed(authorization, mutation.metadata, PermissionAction.EDIT)
        guideService.setGuideRrule(mutation.metadata.id, mutation.metadata.version, rrule)
        return true
    }

    @Field
    suspend fun reorderSteps(
        authorization: AuthenticationContext,
        mutation: GuideMutation,
        stepIds: List<Long>,
    ): Boolean {
        permissionEvaluator.verifyAllowed(authorization, mutation.metadata, PermissionAction.EDIT)
        guideService.reorderSteps(mutation.metadata.id, mutation.metadata.version, stepIds)
        return true
    }

    @Field
    suspend fun reorderModules(
        authorization: AuthenticationContext,
        mutation: GuideMutation,
        stepId: Long,
        moduleIds: List<Long>,
    ): Boolean {
        permissionEvaluator.verifyAllowed(authorization, mutation.metadata, PermissionAction.EDIT)
        guideService.reorderModules(mutation.metadata.id, mutation.metadata.version, stepId, moduleIds)
        return true
    }
}
