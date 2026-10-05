package bosca.feeds.configuration

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

/**
 * Declares the GraphQL schema files the feeds module contributes. KSP processes this to generate
 * `FeedsSchemaRegistrar`, which the server merges into the runtime schema.
 */
@Schemas
interface SchemaRegistrar {

    /** The `feeds` namespace: `Query.feeds` / `Mutation.feeds`, FeedSource + its mutations. */
    @Schema("feeds.graphqls")
    val feeds: String
}
