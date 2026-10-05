package bosca.content.metadata.graphql

import bosca.content.graphql.MetadataBatchFilter
import bosca.content.metadata.model.Guide
import bosca.content.metadata.model.GuideStep
import bosca.content.metadata.model.GuideStepContext
import bosca.content.metadata.model.GuideStepModule
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.service.GuideService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.Batch
import bosca.graphql.BatchContext
import bosca.graphql.BatchFilter
import bosca.graphql.BatchItem
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.BatchKey
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.graphql.BatchLoaderEnvironment

@TypeController("GuideStep")
class GuideStepController(
    private val service: MetadataService,
    private val guides: GuideService,
    private val permissionEvaluator: MetadataPermissionEvaluator,
) : GraphQLController<GuideStepContext> {

    @Field
    fun id(step: GuideStepContext) = step.guideStep.id

    @Field
    fun date(step: GuideStepContext) = step.date

    @Field
    suspend fun metadata(authentication: AuthenticationContext, batch: Batch<MetadataCacheKeyId, Metadata>, env: BatchLoaderEnvironment) {
        val templateKeys = env.keyContextsList.map {
            val guide = (it as BatchContext<*>).context as GuideStepContext
            MetadataCacheKeyId(guide.guideStep.stepMetadataId ?: error("GuideStep does not have a metadata"), guide.guideStep.stepMetadataVersion ?: 1)
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
    suspend fun modules(batch: Batch<MetadataCacheKeyId, List<GuideStepModule>>) {
        guides.addGuideStepModulesToBatch(batch)
        batch.ensureNotNull(emptyList())
    }
}
