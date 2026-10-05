package bosca.meilisearch.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.meilisearch.admin.model.MeilisearchSynonym

/**
 * Resolves fields on the MeilisearchSynonym GraphQL type.
 */
@TypeController
class MeilisearchSynonymController : GraphQLController<MeilisearchSynonym> {

    @Field fun word(synonym: MeilisearchSynonym) = synonym.word
    @Field fun synonyms(synonym: MeilisearchSynonym) = synonym.synonyms
}
