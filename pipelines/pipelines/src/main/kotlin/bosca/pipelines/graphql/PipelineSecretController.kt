package bosca.pipelines.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.pipelines.model.PipelineSecret

/**
 * Field wiring for the `PipelineSecret` GraphQL type — node-secret metadata only.
 * Deliberately projects only the name + timestamps; the encrypted value is never offered
 * over GraphQL (and the plaintext exists only transiently at execution).
 */
@TypeController(type = "PipelineSecret")
class PipelineSecretController : GraphQLController<PipelineSecret> {

    @Field
    fun name(source: PipelineSecret): String = source.name

    @Field
    fun createdAt(source: PipelineSecret): bosca.serialization.OffsetDateTime = source.createdAt

    @Field
    fun modifiedAt(source: PipelineSecret): bosca.serialization.OffsetDateTime = source.modifiedAt
}
