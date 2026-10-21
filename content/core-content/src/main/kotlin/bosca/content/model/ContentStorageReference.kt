package bosca.content.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/**
 * Database evidence for a possible content asset, including soft-deleted content and history.
 * [version] is Bosca's logical metadata version, never a provider object version.
 * [supplementaryKey] is the supplementary record's key, not an object-storage path.
 * Historical supplementary records have no stored UUID: their null [supplementaryId] must
 * remain unresolved rather than borrowing a current attachment's ID.
 *
 * Rows without an upload timestamp are included so consumers can account for absent evidence.
 * In particular, historical metadata has no stored upload timestamp. Neither an upload timestamp
 * nor a length establishes that matching bytes exist in storage or in a backup.
 */
@Serializable
data class ContentStorageReference(
    val kind: ContentStorageReferenceKind,
    @ColumnName("content_id")
    @Contextual
    val contentId: UUID,
    val version: Int?,
    @ColumnName("supplementary_id")
    @Contextual
    val supplementaryId: UUID?,
    @ColumnName("supplementary_key")
    val supplementaryKey: String?,
    @ColumnName("content_length")
    val contentLength: Long?,
    @Contextual
    val uploaded: OffsetDateTime?,
    val deleted: Boolean,
)
