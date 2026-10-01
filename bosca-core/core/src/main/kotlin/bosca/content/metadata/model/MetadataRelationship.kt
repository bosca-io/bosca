package bosca.content.metadata.model

import bosca.content.model.ContentRelationship
import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@BatchKey(type = MetadataCacheKeyId::class)
@Serializable
data class MetadataRelationship(
    @ColumnName("metadata1_id")
    @Contextual
    val metadataId1: UUID,
    @ColumnName("metadata2_id")
    @Contextual
    val metadataId2: UUID,
    override val relationship: String,
    @Contextual
    override val attributes: JsonElement? = null
) : ContentRelationship {

    override val id1: UUID
        get() = metadataId1

    override val id2: UUID
        get() = metadataId2

}