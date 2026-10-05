package bosca.content.metadata.graphql

import bosca.content.metadata.model.DataTemplate
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.DataTemplateService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext

@TypeController
class DataTemplateController(
    private val service: DataTemplateService,
    val metadataService: MetadataService,
    val permissionEvaluator: MetadataPermissionEvaluator
) : GraphQLController<DataTemplate> {

    @Field
    fun type(template: DataTemplate) = template.type

    @Field
    fun defaultAttributes(template: DataTemplate) = template.defaultAttributes

    @Field
    suspend fun attributes(template: DataTemplate) = service.getTemplateAttributes(template.metadataId, template.version)

    @Field
    suspend fun metadata(authentication: AuthenticationContext?, template: DataTemplate): Metadata? {
        val metadata = metadataService.getById(template.metadataId, template.version) ?: return null
        if (!permissionEvaluator.isAllowed(authentication, metadata, PermissionAction.VIEW)) {
            return null
        }
        return metadata
    }
}
