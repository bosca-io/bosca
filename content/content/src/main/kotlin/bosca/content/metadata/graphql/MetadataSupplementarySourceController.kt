package bosca.content.metadata.graphql

import bosca.content.metadata.model.MetadataSupplementarySource
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController


@TypeController
class MetadataSupplementarySourceController : GraphQLController<MetadataSupplementarySource> {

    @Field
    fun id(metadata: MetadataSupplementarySource) = metadata.supplementary.sourceId

    @Field
    fun identifier(metadata: MetadataSupplementarySource) = metadata.supplementary.sourceIdentifier
}