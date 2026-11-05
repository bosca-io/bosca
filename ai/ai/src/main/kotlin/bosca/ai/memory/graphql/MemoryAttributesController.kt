package bosca.ai.memory.graphql

import bosca.content.metadata.model.MemoryAttributes
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class MemoryAttributesController : GraphQLController<MemoryAttributes> {
    @Field fun scope(value: MemoryAttributes) = value.scope
    @Field fun ownerId(value: MemoryAttributes) = value.ownerId
    @Field fun collectionId(value: MemoryAttributes) = value.collectionId
    @Field fun sourceMetadataId(value: MemoryAttributes) = value.sourceMetadataId
    @Field fun sourceVersion(value: MemoryAttributes) = value.sourceVersion
    @Field fun sourceRevision(value: MemoryAttributes) = value.sourceRevision
    @Field fun conversationId(value: MemoryAttributes) = value.conversationId
    @Field fun expires(value: MemoryAttributes) = value.expires
}
