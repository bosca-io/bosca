package bosca.content.collection.graphql

import bosca.content.collection.model.CollectionSupplementarySource
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController


@TypeController
class CollectionSupplementarySourceController : GraphQLController<CollectionSupplementarySource> {

    @Field
    fun id(source: CollectionSupplementarySource) = source.supplementary.sourceId

    @Field
    fun identifier(source: CollectionSupplementarySource) = source.supplementary.sourceIdentifier
}