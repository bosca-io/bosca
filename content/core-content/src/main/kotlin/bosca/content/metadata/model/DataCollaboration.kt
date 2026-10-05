package bosca.content.metadata.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
class DataCollaboration(
    @ColumnName("metadata_id")
    @Contextual
    val metadataId: UUID = UUID.NIL,
    val version: Int,
    val content: ByteArray = ByteArray(0),
    @Contextual
    val created: OffsetDateTime? = null,
    @Contextual
    val modified: OffsetDateTime? = null
)
