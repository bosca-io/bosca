package bosca.content.metadata.graphql

import bosca.content.metadata.model.DocumentTemplate
import bosca.content.metadata.model.GuideTemplate
import bosca.content.metadata.model.GuideTemplateAttribute
import bosca.content.metadata.model.GuideTemplateStep
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.service.GuideTemplateService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.Batch
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext

@TypeController
class GuideTemplateController(
    val service: GuideTemplateService,
    val metadataService: MetadataService,
    val permissionEvaluator: MetadataPermissionEvaluator
) : GraphQLController<GuideTemplate> {

    @Field
    fun type(template: GuideTemplate) = template.type

    @Field
    fun configuration(template: GuideTemplate) = template.configuration

    @Field
    fun defaultAttributes(template: GuideTemplate) = template.defaultAttributes

    @Field
    suspend fun attributes(batch: Batch<MetadataCacheKeyId, List<GuideTemplateAttribute>>) {
        service.addTemplateAttributesToBatch(batch)
        batch.ensureNotNull(emptyList())
    }

    @Field
    fun rrule(template: GuideTemplate) = template.rrule

    @Field
    suspend fun steps(batch: Batch<MetadataCacheKeyId, List<GuideTemplateStep>>) {
        service.addTemplateStepsToBatch(batch)
        batch.ensureNotNull(emptyList())
    }

    @Field
    suspend fun metadata(authentication: AuthenticationContext?, template: GuideTemplate): Metadata? {
        val metadata = metadataService.getById(template.metadataId, template.version) ?: return null
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.VIEW)
        return metadata
    }
}