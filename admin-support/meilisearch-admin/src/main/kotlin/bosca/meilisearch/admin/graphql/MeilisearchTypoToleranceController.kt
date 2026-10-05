package bosca.meilisearch.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.meilisearch.admin.model.MeilisearchMinWordSize
import bosca.meilisearch.admin.model.MeilisearchTypoTolerance

/**
 * Resolves fields on the MeilisearchTypoTolerance GraphQL type.
 */
@TypeController
class MeilisearchTypoToleranceController : GraphQLController<MeilisearchTypoTolerance> {

    @Field fun enabled(tt: MeilisearchTypoTolerance) = tt.enabled
    @Field fun minWordSizeForTypos(tt: MeilisearchTypoTolerance): MeilisearchMinWordSize? = tt.minWordSizeForTypos
    @Field fun disableOnWords(tt: MeilisearchTypoTolerance) = tt.disableOnWords
    @Field fun disableOnAttributes(tt: MeilisearchTypoTolerance) = tt.disableOnAttributes
}
