package bosca.comments.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A metadata item that has at least one comment — the unit of the "content with comments" list,
 * ordered by most-recent comment. Carries only the coordinates needed to resolve the metadata.
 */
@Serializable
data class CommentedMetadata(
    @ColumnName("metadata_id")
    @Contextual
    val metadataId: UUID,
    val version: Int,
)
