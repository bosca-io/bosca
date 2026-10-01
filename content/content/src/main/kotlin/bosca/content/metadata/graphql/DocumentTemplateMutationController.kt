package bosca.content.metadata.graphql

import bosca.attributes.TemplateAttributeInput
import bosca.content.metadata.model.DocumentTemplateContainerInput
import bosca.content.metadata.model.DocumentTemplateMutation
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.DocumentTemplateService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import kotlinx.serialization.json.JsonElement


@TypeController
class DocumentTemplateMutationController(
    private val templateService: DocumentTemplateService,
    private val permissionEvaluator: MetadataPermissionEvaluator,
) : GraphQLController<DocumentTemplateMutation> {

    @Field
    suspend fun addAttribute(
        authentication: AuthenticationContext,
        mutation: DocumentTemplateMutation,
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
        mutation: DocumentTemplateMutation,
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
        mutation: DocumentTemplateMutation,
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
    suspend fun setConfiguration(
        authentication: AuthenticationContext,
        mutation: DocumentTemplateMutation,
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
        mutation: DocumentTemplateMutation,
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
    suspend fun setSchema(
        authentication: AuthenticationContext,
        mutation: DocumentTemplateMutation,
        schema: JsonElement
    ): Metadata {
        permissionEvaluator.verifyAllowed(authentication, mutation.metadata, PermissionAction.EDIT)
        templateService.setSchema(
            mutation.metadata.id,
            mutation.metadata.version,
            schema
        )
        return mutation.metadata
    }

    @Field
    suspend fun addContainer(
        authentication: AuthenticationContext,
        mutation: DocumentTemplateMutation,
        container: DocumentTemplateContainerInput,
        sort: Int
    ): Metadata {
        permissionEvaluator.verifyAllowed(authentication, mutation.metadata, PermissionAction.EDIT)
        templateService.addContainer(
            mutation.metadata.id,
            mutation.metadata.version,
            container,
            sort
        )
        return mutation.metadata
    }

    @Field
    suspend fun deleteContainer(
        authentication: AuthenticationContext,
        mutation: DocumentTemplateMutation,
        containerId: String,
    ): Metadata {
        permissionEvaluator.verifyAllowed(authentication, mutation.metadata, PermissionAction.EDIT)
        templateService.deleteContainer(
            mutation.metadata.id,
            mutation.metadata.version,
            containerId
        )
        return mutation.metadata
    }

    @Field
    suspend fun setContainers(
        authentication: AuthenticationContext,
        mutation: DocumentTemplateMutation,
        containers: List<DocumentTemplateContainerInput>
    ): Metadata {
        permissionEvaluator.verifyAllowed(authentication, mutation.metadata, PermissionAction.EDIT)
        templateService.setContainers(
            mutation.metadata.id,
            mutation.metadata.version,
            containers
        )
        return mutation.metadata
    }

    @Field
    suspend fun setContent(
        authentication: AuthenticationContext,
        mutation: DocumentTemplateMutation,
        content: JsonElement
    ): Metadata {
        permissionEvaluator.verifyAllowed(authentication, mutation.metadata, PermissionAction.EDIT)
        templateService.setContent(
            mutation.metadata.id,
            mutation.metadata.version,
            content
        )
        return mutation.metadata
    }
}