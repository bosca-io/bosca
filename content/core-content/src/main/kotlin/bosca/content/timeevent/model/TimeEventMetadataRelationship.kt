package bosca.content.timeevent.model

import bosca.content.model.ContentRelationship
import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Represents a typed relationship between a time event and a metadata item,
 * enabling a single time event to reference multiple metadata items with
 * different relationship semantics (e.g. "slide", "resource", "reference").
 *
 * Implements [ContentRelationship] so that the image optimization pipeline
 * can generate cropped and resized variants from crop coordinates stored
 * in this relationship's [attributes].
 *
 * The composite key of [timeEventId], [metadataId], and [relationship] ensures
 * that the same metadata item can be linked to a time event under different
 * relationship types, but each combination is unique.
 */
@Serializable
data class TimeEventMetadataRelationship(
    @ColumnName("time_event_id")
    @Contextual
    val timeEventId: UUID,
    @ColumnName("metadata_id")
    @Contextual
    val metadataId: UUID,
    @ColumnName("metadata_version")
    val metadataVersion: Int? = null,
    override val relationship: String,
    @Contextual
    override val attributes: JsonElement? = null
) : ContentRelationship {
    override val id1: UUID get() = timeEventId
    override val id2: UUID get() = metadataId
}
