package bosca.content.metadata.graphql

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.model.MetadataRelationship
import bosca.content.metadata.service.MetadataService
import bosca.graphql.Batch
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class MetadataRelationshipController(
    private val service: MetadataService,
) : GraphQLController<MetadataRelationship> {

    @Field
    fun id(relationship: MetadataRelationship) = relationship.metadataId1

    @Field
    fun relationship(relationship: MetadataRelationship) = relationship.relationship

    @Field
    fun attributes(relationship: MetadataRelationship) = relationship.attributes

    @Field
    suspend fun metadata(batch: Batch<MetadataCacheKeyId, Metadata>) {
        service.getByIdBatched(batch)
    }
}