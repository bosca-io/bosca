package bosca.content.collection.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable

class CollectionCollaboration(
    @ColumnName("collection_id")
    @Contextual
    val collectionId: UUID = UUID.NIL,
    @ColumnName("language_tag")
    val languageTag: String = "",
    val content: ByteArray = ByteArray(0),
    @Contextual
    val created: OffsetDateTime? = null,
    @Contextual
    val modified: OffsetDateTime? = null
)