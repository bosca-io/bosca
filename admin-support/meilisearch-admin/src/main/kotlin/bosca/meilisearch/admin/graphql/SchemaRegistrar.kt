package bosca.meilisearch.admin.graphql

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

/**
 * Registers the Meilisearch admin GraphQL schema file so it is included
 * in the composed schema at server startup.
 */
@Schemas
interface SchemaRegistrar {

    @Schema("meilisearch-admin.graphqls")
    val meilisearchAdmin: String
}
