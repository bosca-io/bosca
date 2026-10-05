package bosca.calendar.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Display-time properties of a calendar that hang off a parent Metadata, the
 * same way Data, Document, and Guide hang off a metadata. Identity comes from
 * the (metadataId, version) pair: every metadata version that opts into being
 * a calendar has exactly one row here. The owning metadata is expected to have
 * contentType `bosca/v-calendar`.
 *
 * Read and write access is governed entirely by the parent metadata's
 * permissions through MetadataPermissionEvaluator; there is no per-calendar
 * permission table.
 */
@Serializable
data class Calendar(
    @ColumnName("metadata_id")
    @Contextual
    val metadataId: UUID,
    val version: Int,
    val color: String = "#3b82f6",
    val description: String = "",
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now()
)
