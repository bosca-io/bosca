package bosca.git.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A comment anchored to a specific line in a pull request diff. The comment
 * is positioned by [filePath] and either [oldLineNumber] (deletion side) or
 * [newLineNumber] (addition side). When the source branch is force-pushed,
 * the [commitSha] is compared against the new HEAD to detect whether the
 * anchoring context has changed, marking the comment as [outdated].
 */
@Serializable
data class ReviewComment(
    @Contextual val id: UUID = UUID.NIL,
    @Contextual @ColumnName("review_id") val reviewId: UUID,
    @Contextual @ColumnName("pull_request_id") val pullRequestId: UUID,
    @Contextual @ColumnName("author_id") val authorId: UUID,
    @ColumnName("file_path") val filePath: String,
    @ColumnName("old_line_number") val oldLineNumber: Int? = null,
    @ColumnName("new_line_number") val newLineNumber: Int? = null,
    @ColumnName("commit_sha") val commitSha: String,
    val content: String,
    val outdated: Boolean = false,
    val resolved: Boolean = false,
    @Contextual val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual val updated: OffsetDateTime = OffsetDateTime.now()
)
