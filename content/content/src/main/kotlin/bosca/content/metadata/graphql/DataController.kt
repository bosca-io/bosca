package bosca.content.metadata.graphql

import bosca.content.metadata.model.Data
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext

@TypeController
class DataController(
    private val metadataService: MetadataService,
    private val permissionEvaluator: MetadataPermissionEvaluator,
) : GraphQLController<Data> {

    @Field
    fun type(data: Data) = data.type

    @Field
    suspend fun template(authentication: AuthenticationContext?, data: Data): Metadata? {
        val templateId = data.templateMetadataId ?: return null
        val templateVersion = data.templateMetadataVersion ?: return null
        val metadata = metadataService.getById(templateId, templateVersion) ?: return null
        if (!permissionEvaluator.isAllowed(authentication, metadata, PermissionAction.VIEW)) {
            return null
        }
        return metadata
    }
}
