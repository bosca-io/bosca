package bosca.content.metadata.graphql

import bosca.content.graphql.MetadataBatchFilter
import bosca.content.metadata.model.GuideStepContext
import bosca.content.metadata.model.GuideTemplateStep
import bosca.content.metadata.model.GuideTemplateStepModule
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.service.GuideTemplateService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.Batch
import bosca.graphql.BatchContext
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.graphql.BatchLoaderEnvironment

@TypeController
class GuideTemplateStepController(
    private val service: MetadataService,
    private val guides: GuideTemplateService,
    private val permissionEvaluator: MetadataPermissionEvaluator,
) : GraphQLController<GuideTemplateStep> {

    @Field
    fun id(step: GuideTemplateStep) = step.id

    @Field
    suspend fun metadata(authentication: AuthenticationContext, env: BatchLoaderEnvironment, batch: Batch<MetadataCacheKeyId, Metadata>) {
        val templateKeys = env.keyContextsList.map {
            val guide = (it as BatchContext<*>).context as GuideTemplateStep
            MetadataCacheKeyId(guide.templateMetadataId ?: error("GuideTemplateStep does not have a metadata"), guide.templateMetadataVersion ?: 1)
        }
        val templateBatch = Batch<MetadataCacheKeyId, Metadata>(templateKeys)
        service.getByIdBatched(templateBatch)
        batch.filter = MetadataBatchFilter(authentication, permissionEvaluator)
        batch.keys.forEachIndexed { index, key ->
            val templateKey = templateKeys[index]
            val template = templateBatch.getData(templateKey) ?: return@forEachIndexed
            batch.setData(key, template)
        }
    }

    @Field
    suspend fun modules(batch: Batch<MetadataCacheKeyId, List<GuideTemplateStepModule>>) {
        guides.addTemplateStepModulesToBatch(batch)
        batch.ensureNotNull(emptyList())
    }
}
