package bosca.meilisearch.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.meilisearch.admin.model.MeilisearchMinWordSize

/**
 * Resolves fields on the MeilisearchMinWordSize GraphQL type.
 */
@TypeController
class MeilisearchMinWordSizeController : GraphQLController<MeilisearchMinWordSize> {

    @Field fun oneTypo(mw: MeilisearchMinWordSize) = mw.oneTypo
    @Field fun twoTypos(mw: MeilisearchMinWordSize) = mw.twoTypos
}
