package bosca.content.collection.graphql

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionSupplementary
import bosca.content.collection.model.CollectionSupplementaryContent
import bosca.content.collection.model.CollectionSupplementarySource
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

class CollectionSupplementaryContext(
    val collection: Collection,
    val supplementary: CollectionSupplementary,
)

@TypeController("CollectionSupplementary")
class CollectionSupplementaryController : GraphQLController<CollectionSupplementaryContext> {

    @Field
    fun id(supplementary: CollectionSupplementaryContext) = supplementary.supplementary.id

    @Field
    fun planId(supplementary: CollectionSupplementaryContext) = supplementary.supplementary.planId

    @Field
    fun name(supplementary: CollectionSupplementaryContext) = supplementary.supplementary.name

    @Field
    fun key(supplementary: CollectionSupplementaryContext) = supplementary.supplementary.key

    @Field
    fun collectionId(supplementary: CollectionSupplementaryContext) = supplementary.collection.id

    @Field
    fun created(supplementary: CollectionSupplementaryContext) = supplementary.supplementary.created

    @Field
    fun modified(supplementary: CollectionSupplementaryContext) = supplementary.supplementary.modified

    @Field
    fun content(supplementary: CollectionSupplementaryContext): CollectionSupplementaryContent {
        return CollectionSupplementaryContent(
            supplementary.collection,
            supplementary.supplementary
        )
    }

    @Field
    fun source(supplementary: CollectionSupplementaryContext): CollectionSupplementarySource? {
        if (supplementary.supplementary.sourceId == null && supplementary.supplementary.sourceIdentifier == null) {
            return null
        }
        return CollectionSupplementarySource(
            supplementary.collection,
            supplementary.supplementary
        )
    }

    @Field
    fun attributes(supplementary: CollectionSupplementaryContext) = supplementary.supplementary.attributes

    @Field
    fun uploaded(supplementary: CollectionSupplementaryContext) = supplementary.supplementary.uploaded
}