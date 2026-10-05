package bosca.content.metadata.graphql

import bosca.content.metadata.model.DocumentTemplate
import bosca.content.metadata.service.DocumentTemplateService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext

object DocumentTemplates

@TypeController
class DocumentTemplatesController(
    private val documentTemplateService: DocumentTemplateService,
    private val metadataService: MetadataService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
) : GraphQLController<DocumentTemplates> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<DocumentTemplate> {
        val templates = documentTemplateService.getAll()
        return templates.filter {
            val metadata = metadataService.getById(it.metadataId, it.version) ?: return@filter false
            metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.VIEW)
        }
    }
}