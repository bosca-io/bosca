package bosca.content.metadata.graphql

import bosca.attributes.TemplateAttributeInput
import bosca.content.metadata.model.GuideType
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.GuideTemplateService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

class GuideTemplateMutation(
    val metadata: Metadata
)

@TypeController
class GuideTemplateMutationController(
    private val templateService: GuideTemplateService,
    private val permissionEvaluator: MetadataPermissionEvaluator,
) : GraphQLController<GuideTemplateMutation> {

    @Field
    suspend fun addAttribute(
        authentication: AuthenticationContext,
        mutation: GuideTemplateMutation,
        attribute: TemplateAttributeInput,
        sort: Int
    ): Metadata {
        permissionEvaluator.verifyAllowed(authentication, mutation.metadata, PermissionAction.EDIT)
        templateService.addAttribute(
            mutation.metadata.id,
            mutation.metadata.version,
            attribute,
            sort
        )
        return mutation.metadata
    }

    @Field
    suspend fun deleteAttribute(
        authentication: AuthenticationContext,
        mutation: GuideTemplateMutation,
        key: String
    ): Metadata {
        permissionEvaluator.verifyAllowed(authentication, mutation.metadata, PermissionAction.EDIT)
        templateService.deleteAttribute(
            mutation.metadata.id,
            mutation.metadata.version,
            key
        )
        return mutation.metadata
    }

    @Field
    suspend fun setAttributes(
        authentication: AuthenticationContext,
        mutation: GuideTemplateMutation,
        attributes: List<TemplateAttributeInput>
    ): Metadata {
        permissionEvaluator.verifyAllowed(authentication, mutation.metadata, PermissionAction.EDIT)
        templateService.setAttributes(
            mutation.metadata.id,
            mutation.metadata.version,
            attributes
        )
        return mutation.metadata
    }

    @Field
    suspend fun setConfiguration(
        authentication: AuthenticationContext,
        mutation: GuideTemplateMutation,
        configuration: JsonElement,
    ): Metadata {
        permissionEvaluator.verifyAllowed(authentication, mutation.metadata, PermissionAction.EDIT)
        templateService.setConfiguration(
            mutation.metadata.id,
            mutation.metadata.version,
            configuration
        )
        return mutation.metadata
    }

    @Field
    suspend fun setDefaultAttributes(
        authentication: AuthenticationContext,
        mutation: GuideTemplateMutation,
        attributes: JsonElement,
    ): Metadata {
        permissionEvaluator.verifyAllowed(authentication, mutation.metadata, PermissionAction.EDIT)
        templateService.setDefaultAttributes(
            mutation.metadata.id,
            mutation.metadata.version,
            attributes
        )
        return mutation.metadata
    }

    @Field
    suspend fun setRrule(
        authentication: AuthenticationContext,
        mutation: GuideTemplateMutation,
        rrule: String,
    ): Metadata {
        permissionEvaluator.verifyAllowed(authentication, mutation.metadata, PermissionAction.EDIT)
        templateService.setRrule(
            mutation.metadata.id,
            mutation.metadata.version,
            rrule
        )
        return mutation.metadata
    }

    @Field
    suspend fun setType(
        authentication: AuthenticationContext,
        mutation: GuideTemplateMutation,
        guideType: GuideType,
    ): Metadata {
        permissionEvaluator.verifyAllowed(authentication, mutation.metadata, PermissionAction.EDIT)
        templateService.setType(
            mutation.metadata.id,
            mutation.metadata.version,
            guideType
        )
        return mutation.metadata
    }

    @Field
    suspend fun addStep(
        authentication: AuthenticationContext,
        mutation: GuideTemplateMutation,
        stepMetadataId: UUID,
        stepMetadataVersion: Int,
    ): Boolean {
        permissionEvaluator.verifyAllowed(authentication, mutation.metadata, PermissionAction.EDIT)
        templateService.addStep(mutation.metadata, stepMetadataId, stepMetadataVersion)
        return true
    }

    @Field
    suspend fun addModule(
        authentication: AuthenticationContext,
        mutation: GuideTemplateMutation,
        moduleMetadataId: UUID,
        moduleMetadataVersion: Int,
        stepId: Long,
    ): Boolean {
        permissionEvaluator.verifyAllowed(authentication, mutation.metadata, PermissionAction.EDIT)
        templateService.addModule(mutation.metadata, stepId, moduleMetadataId, moduleMetadataVersion)
        return true
    }

    @Field
    suspend fun removeModule(
        authentication: AuthenticationContext,
        mutation: GuideTemplateMutation,
        stepId: Long,
        moduleId: Long
    ): Boolean {
        permissionEvaluator.verifyAllowed(authentication, mutation.metadata, PermissionAction.EDIT)
        templateService.removeModule(mutation.metadata, stepId, moduleId)
        return true
    }

    @Field
    suspend fun removeStep(
        authentication: AuthenticationContext,
        mutation: GuideTemplateMutation,
        stepId: Long,
    ): Boolean {
        permissionEvaluator.verifyAllowed(authentication, mutation.metadata, PermissionAction.EDIT)
        templateService.removeStep(mutation.metadata, stepId)
        return true
    }

    @Field
    suspend fun reorderModules(
        authentication: AuthenticationContext,
        mutation: GuideTemplateMutation,
        stepId: Long,
        moduleIds: List<Long>,
    ): Boolean {
        permissionEvaluator.verifyAllowed(authentication, mutation.metadata, PermissionAction.EDIT)
        templateService.reorderModules(mutation.metadata, stepId, moduleIds)
        return true
    }

    @Field
    suspend fun reorderSteps(
        authentication: AuthenticationContext,
        mutation: GuideTemplateMutation,
        stepIds: List<Long>,
    ): Boolean {
        permissionEvaluator.verifyAllowed(authentication, mutation.metadata, PermissionAction.EDIT)
        templateService.reorderSteps(mutation.metadata, stepIds)
        return true
    }
}