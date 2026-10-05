package bosca.content.metadata.graphql

import bosca.attributes.TemplateAttributeInput
import bosca.content.metadata.model.DataTemplateMutation
import bosca.content.metadata.model.DataType
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.DataTemplateService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import kotlinx.serialization.json.JsonElement


@TypeController
class DataTemplateMutationController(
    private val templateService: DataTemplateService,
    private val permissionEvaluator: MetadataPermissionEvaluator,
) : GraphQLController<DataTemplateMutation> {

    @Field
    suspend fun addAttribute(
        authentication: AuthenticationContext,
        mutation: DataTemplateMutation,
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
        mutation: DataTemplateMutation,
        key: String
    ): Metadata? {
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
        mutation: DataTemplateMutation,
        attributes: List<TemplateAttributeInput>
    ): Metadata? {
        permissionEvaluator.verifyAllowed(authentication, mutation.metadata, PermissionAction.EDIT)
        templateService.setAttributes(
            mutation.metadata.id,
            mutation.metadata.version,
            attributes
        )
        return mutation.metadata
    }

    @Field
    suspend fun setDefaultAttributes(
        authentication: AuthenticationContext,
        mutation: DataTemplateMutation,
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
    suspend fun setType(
        authentication: AuthenticationContext,
        mutation: DataTemplateMutation,
        type: DataType,
    ): Metadata {
        permissionEvaluator.verifyAllowed(authentication, mutation.metadata, PermissionAction.EDIT)
        templateService.setType(
            mutation.metadata.id,
            mutation.metadata.version,
            type
        )
        return mutation.metadata
    }
}