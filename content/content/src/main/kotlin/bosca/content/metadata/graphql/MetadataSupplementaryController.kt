package bosca.content.metadata.graphql

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataSupplementary
import bosca.content.metadata.model.MetadataSupplementaryContent
import bosca.content.metadata.model.MetadataSupplementarySource
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

class MetadataSupplementaryContext(
    val metadata: Metadata,
    val supplementary: MetadataSupplementary,
)

@TypeController("MetadataSupplementary")
class MetadataSupplementaryController : GraphQLController<MetadataSupplementaryContext> {

    @Field
    fun id(supplementary: MetadataSupplementaryContext) = supplementary.supplementary.id

    @Field
    fun planId(supplementary: MetadataSupplementaryContext) = supplementary.supplementary.planId

    @Field
    fun name(supplementary: MetadataSupplementaryContext) = supplementary.supplementary.name

    @Field
    fun key(supplementary: MetadataSupplementaryContext) = supplementary.supplementary.key

    @Field
    fun metadataId(supplementary: MetadataSupplementaryContext) = supplementary.metadata.id

    @Field
    fun created(supplementary: MetadataSupplementaryContext) = supplementary.supplementary.created

    @Field
    fun modified(supplementary: MetadataSupplementaryContext) = supplementary.supplementary.modified

    @Field
    fun content(supplementary: MetadataSupplementaryContext): MetadataSupplementaryContent {
        return MetadataSupplementaryContent(
            supplementary.metadata,
            supplementary.supplementary
        )
    }

    @Field
    fun source(supplementary: MetadataSupplementaryContext): MetadataSupplementarySource? {
        if (supplementary.supplementary.sourceId == null && supplementary.supplementary.sourceIdentifier == null) {
            return null
        }
        return MetadataSupplementarySource(
            supplementary.metadata,
            supplementary.supplementary
        )
    }

    @Field
    fun attributes(supplementary: MetadataSupplementaryContext) = supplementary.supplementary.attributes

    @Field
    fun uploaded(supplementary: MetadataSupplementaryContext) = supplementary.supplementary.uploaded
}