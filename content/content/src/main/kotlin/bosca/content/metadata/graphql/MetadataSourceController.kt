package bosca.content.metadata.graphql

import bosca.content.metadata.model.Metadata
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.GroupEvaluator
import bosca.source.model.Source
import bosca.source.service.SourceService
import bosca.security.service.AuthenticationContext

class MetadataSource(val metadata: Metadata)

@TypeController
class MetadataSourceController(
    val sourceService: SourceService,
    val groupEvaluator: GroupEvaluator
) : GraphQLController<MetadataSource> {

    @Field
    fun id(source: MetadataSource) = source.metadata.sourceId

    @Field
    fun identifier(source: MetadataSource) = source.metadata.sourceIdentifier

    @Field
    fun url(source: MetadataSource) = source.metadata.sourceUrl

    @Field
    fun status(source: MetadataSource) = source.metadata.sourceStatus

    @Field
    suspend fun source(authentication: AuthenticationContext, source: MetadataSource): Source? {
        groupEvaluator.verifyHasSaGroup(authentication)
        return source.metadata.sourceId?.let {
            sourceService.getById(it)
        }
    }
}