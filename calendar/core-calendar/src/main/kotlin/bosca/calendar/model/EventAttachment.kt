package bosca.calendar.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A link between a calendar event and a metadata item or collection,
 * with an optional relationship label (e.g. "attachment", "agenda",
 * "minutes") and freeform attributes.
 */
@Serializable
data class EventAttachment(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("event_id")
    @Contextual
    val eventId: UUID,
    @ColumnName("metadata_id")
    @Contextual
    val metadataId: UUID? = null,
    @ColumnName("collection_id")
    @Contextual
    val collectionId: UUID? = null,
    val relationship: String = "attachment",
    @Contextual
    val attributes: JsonElement? = null,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now()
)
