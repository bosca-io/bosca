package bosca.content.metadata.graphql

import bosca.content.attributes.model.TemplateAttribute
import bosca.content.collection.model.CollectionTemplate
import bosca.content.metadata.model.CollectionTemplateFilters
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.CollectionTemplateService
import bosca.content.metadata.service.MetadataService
import bosca.content.ordering.Ordering
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json


@TypeController
class CollectionTemplateController(
    val service: CollectionTemplateService,
    val metadataService: MetadataService,
    val metadataPermissionEvaluator: MetadataPermissionEvaluator,
    val json: Json
) : GraphQLController<CollectionTemplate> {

    @Field
    fun configuration(template: CollectionTemplate) = template.configuration

    @Field
    fun defaultAttributes(template: CollectionTemplate) = template.defaultAttributes

    @Field
    fun filters(template: CollectionTemplate) = json.decodeFromJsonElement(CollectionTemplateFilters.serializer(), template.filters)

    @Field
    fun ordering(template: CollectionTemplate) = template.ordering?.let { json.decodeFromJsonElement(ListSerializer(Ordering.serializer()), it) }

    @Field
    suspend fun metadata(authentication: AuthenticationContext, template: CollectionTemplate): Metadata? {
        val metadata = metadataService.getById(template.metadataId, template.version) ?: return null
        metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.VIEW)
        return metadata
    }

    @Field
    suspend fun attributes(template: CollectionTemplate) =
        service.getCollectionTemplateAttributes(template.metadataId, template.version)
            .map { TemplateAttribute(collectionAttribute = it) }
}