package bosca.content.metadata.graphql

import bosca.content.metadata.model.DataTemplate
import bosca.content.metadata.service.DataTemplateService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext

object DataTemplates

@TypeController
class DataTemplatesController(
    private val dataTemplateService: DataTemplateService,
    private val metadataService: MetadataService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
) : GraphQLController<DataTemplates> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<DataTemplate> {
        val templates = dataTemplateService.getAll()
        return templates.filter {
            val metadata = metadataService.getById(it.metadataId, it.version) ?: return@filter false
            metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.VIEW)
        }
    }
}
