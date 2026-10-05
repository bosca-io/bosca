package bosca.content.metadata.graphql

import bosca.content.metadata.model.GuideTemplate
import bosca.content.metadata.service.GuideTemplateService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext

object GuideTemplates

@TypeController
class GuideTemplatesController(
    private val guideTemplateService: GuideTemplateService,
    private val metadataService: MetadataService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
) : GraphQLController<GuideTemplates> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<GuideTemplate> {
        val templates = guideTemplateService.getAll()
        return templates.filter {
            val metadata = metadataService.getById(it.metadataId, it.version) ?: return@filter false
            metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.VIEW)
        }
    }
}