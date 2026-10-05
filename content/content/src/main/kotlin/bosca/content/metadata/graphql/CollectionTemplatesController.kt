package bosca.content.metadata.graphql

import bosca.content.collection.model.CollectionTemplate
import bosca.content.metadata.service.CollectionTemplateService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext

object CollectionTemplates

@TypeController
class CollectionTemplatesController(
    private val metadataService: MetadataService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
    private val collectionTemplateService: CollectionTemplateService
) : GraphQLController<CollectionTemplates> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<CollectionTemplate> {
        val templates = collectionTemplateService.getAll()
        return templates.filter {
            val metadata = metadataService.getById(it.metadataId, it.version) ?: return@filter false
            metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.VIEW)
        }
    }
}