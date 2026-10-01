package bosca.content.metadata.graphql

import bosca.content.graphql.MetadataBatchFilter
import bosca.content.metadata.model.GuideTemplateStepModule
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.Batch
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext

@TypeController
class GuideTemplateStepModuleController(
    private val service: MetadataService,
    private val permissionEvaluator: MetadataPermissionEvaluator,
) : GraphQLController<GuideTemplateStepModule> {

    @Field
    fun id(module: GuideTemplateStepModule) = module.id

    @Field
    suspend fun metadata(authentication: AuthenticationContext, batch: Batch<MetadataCacheKeyId, Metadata>) {
        batch.filter = MetadataBatchFilter(authentication, permissionEvaluator)
        service.getByIdBatched(batch)
    }
}