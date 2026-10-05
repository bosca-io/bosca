package bosca.content.collection.model

import bosca.content.model.ContentRelationship
import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class CollectionLanguageVariantMetadataRelationship(
    @ColumnName("collection_id")
    @Contextual
    val collectionId: UUID,
    @ColumnName("metadata_id")
    @Contextual
    val metadataId: UUID,
    @ColumnName("language_tag")
    val languageTag: String,
    override val relationship: String,
    @Contextual
    override val attributes: JsonElement? = null
) : ContentRelationship {

    override val id1: UUID
        get() = collectionId

    override val id2: UUID
        get() = metadataId

}