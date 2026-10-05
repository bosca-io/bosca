package bosca.meilisearch.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.meilisearch.admin.model.MeilisearchEmbedderEntry
import kotlinx.serialization.json.JsonElement

/**
 * Resolves fields on the MeilisearchEmbedderEntry GraphQL type,
 * exposing the embedder source, model, dimensions, document template,
 * and provider-specific configuration (URL, request/response mapping, headers).
 */
@TypeController
class MeilisearchEmbedderEntryController : GraphQLController<MeilisearchEmbedderEntry> {

    @Field fun name(entry: MeilisearchEmbedderEntry) = entry.name
    @Field fun source(entry: MeilisearchEmbedderEntry) = entry.source
    @Field fun model(entry: MeilisearchEmbedderEntry) = entry.model
    @Field fun dimensions(entry: MeilisearchEmbedderEntry) = entry.dimensions
    @Field fun documentTemplate(entry: MeilisearchEmbedderEntry) = entry.documentTemplate
    @Field fun documentTemplateMaxBytes(entry: MeilisearchEmbedderEntry) = entry.documentTemplateMaxBytes
    @Field fun url(entry: MeilisearchEmbedderEntry) = entry.url
    @Field fun request(entry: MeilisearchEmbedderEntry): JsonElement? = entry.request
    @Field fun response(entry: MeilisearchEmbedderEntry): JsonElement? = entry.response
    @Field fun headers(entry: MeilisearchEmbedderEntry): JsonElement? = entry.headers
}
