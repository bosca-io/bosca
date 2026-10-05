package bosca.comments.repository

import bosca.db.annotation.ColumnName
import kotlinx.serialization.Serializable

/**
 * Single-column wrapper for `select comment_id from
 * metadata_comment_likes …` queries. Bosca's KSP repository generator
 * can't synthesize a row reader for `List<Long>` directly (it needs
 * a model declaration to bind the column to a property), so the
 * service unwraps to `List<Long>` after the read.
 */
@Serializable
data class CommentLikeReference(
    @ColumnName("comment_id")
    val commentId: Long,
)
