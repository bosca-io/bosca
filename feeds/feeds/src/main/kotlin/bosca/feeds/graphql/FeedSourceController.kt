package bosca.feeds.graphql

import bosca.feeds.model.FeedSource
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.source.service.SourceService
import kotlinx.serialization.json.JsonElement

/**
 * Resolves the GraphQL `FeedSource` type. The operational fields (`enabled`, `url`,
 * `ownerProfileId`, `created`, `modified`) resolve directly from the [FeedSource] record; the
 * identity/content fields (`id`, `name`, `description`, `configuration`) resolve from the backing
 * content `Source`. `configuration` is the secret-free FeedConfiguration JSON.
 */
@TypeController
class FeedSourceController(
    private val sourceService: SourceService,
) : GraphQLController<FeedSource> {

    @Field
    fun id(source: FeedSource): UUID = source.sourceId

    @Field
    fun enabled(source: FeedSource): Boolean = source.enabled

    @Field
    fun url(source: FeedSource): String = source.url

    @Field
    fun ownerProfileId(source: FeedSource): UUID? = source.ownerProfileId

    @Field
    fun created(source: FeedSource): OffsetDateTime = source.created

    @Field
    fun modified(source: FeedSource): OffsetDateTime = source.modified

    @Field
    suspend fun name(source: FeedSource): String = sourceService.getById(source.sourceId).name

    @Field
    suspend fun description(source: FeedSource): String = sourceService.getById(source.sourceId).description

    @Field
    suspend fun configuration(source: FeedSource): JsonElement = sourceService.getById(source.sourceId).configuration
}
