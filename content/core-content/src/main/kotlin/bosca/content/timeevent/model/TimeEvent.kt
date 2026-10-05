package bosca.content.timeevent.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class TimeEvent(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("metadata_id")
    @Contextual
    val metadataId: UUID,
    @ColumnName("metadata_version")
    val metadataVersion: Int,
    val type: String,
    @ColumnName("start_offset_ms")
    val startOffsetMs: Long,
    @ColumnName("end_offset_ms")
    val endOffsetMs: Long? = null,
    val sort: Int = 0,
    @Contextual
    val attributes: JsonElement = JsonObject(emptyMap()),
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now()
)
