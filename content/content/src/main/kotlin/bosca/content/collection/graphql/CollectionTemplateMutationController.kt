package bosca.content.collection.graphql

import bosca.attributes.TemplateAttributeInput
import bosca.content.collection.model.CollectionTemplateMutation
import bosca.content.metadata.model.CollectionTemplateFiltersInput
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.CollectionTemplateService
import bosca.content.ordering.OrderingInput
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import kotlinx.serialization.json.JsonElement

@TypeController
class CollectionTemplateMutationController(
    private val templateService: CollectionTemplateService,
    private val permissionEvaluator: MetadataPermissionEvaluator,
) : GraphQLController<CollectionTemplateMutation> {

    @Field
    suspend fun addAttribute(
        authentication: AuthenticationContext,
        mutation: CollectionTemplateMutation,
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
        mutation: CollectionTemplateMutation,
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
        mutation: CollectionTemplateMutation,
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
        mutation: CollectionTemplateMutation,
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
        mutation: CollectionTemplateMutation,
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
    suspend fun setFilters(
        authentication: AuthenticationContext,
        mutation: CollectionTemplateMutation,
        filters: CollectionTemplateFiltersInput
    ): Metadata {
        permissionEvaluator.verifyAllowed(authentication, mutation.metadata, PermissionAction.EDIT)
        templateService.setFilters(
            mutation.metadata.id,
            mutation.metadata.version,
            filters
        )
        return mutation.metadata
    }

    @Field
    suspend fun setOrdering(
        authentication: AuthenticationContext,
        mutation: CollectionTemplateMutation,
        ordering: List<OrderingInput>
    ): Metadata {
        permissionEvaluator.verifyAllowed(authentication, mutation.metadata, PermissionAction.EDIT)
        templateService.setOrdering(
            mutation.metadata.id,
            mutation.metadata.version,
            ordering
        )
        return mutation.metadata
    }
}