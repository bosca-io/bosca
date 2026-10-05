package bosca.content.metadata.graphql

import bosca.content.metadata.model.DocumentTemplate
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.DocumentTemplateService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

@TypeController
class DocumentTemplateController(
    private val service: DocumentTemplateService,
    private val metadataService: MetadataService,
    private val permissionEvaluator: MetadataPermissionEvaluator,
    private val json: Json
) : GraphQLController<DocumentTemplate> {

    @Field
    fun configuration(template: DocumentTemplate) = template.configuration

    @Field
    fun schema(template: DocumentTemplate) = template.schema

    @Field
    fun content(template: DocumentTemplate) = template.content?.let { json.encodeToJsonElement(it) }

    @Field
    fun defaultAttributes(template: DocumentTemplate) = template.defaultAttributes

    @Field
    suspend fun attributes(template: DocumentTemplate) = service.getTemplateAttributes(template.metadataId, template.version)

    @Field
    suspend fun containers(template: DocumentTemplate) = service.getTemplateContainers(template.metadataId, template.version)

    @Field
    suspend fun metadata(authentication: AuthenticationContext?, template: DocumentTemplate): Metadata? {
        val metadata = metadataService.getById(template.metadataId, template.version) ?: return null
        if (!permissionEvaluator.isAllowed(authentication, metadata, PermissionAction.VIEW)) {
            return null
        }
        return metadata
    }
}