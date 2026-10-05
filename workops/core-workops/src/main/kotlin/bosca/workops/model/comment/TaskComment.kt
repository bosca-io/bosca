package bosca.workops.model.comment

import bosca.comments.model.CommentStatus
import bosca.db.annotation.ColumnName
import bosca.profile.model.ProfileVisibility
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One comment on a task (R6). Mirrors the Rust metadata-comment shape
 * and reuses the public-schema `comment_status` and `profile_visibility`
 * enums — every column except the entity coordinates is identical.
 *
 * Replies link via `parentId` to a peer in the same task thread; the
 * service rejects parent ids that belong to a different task before
 * insert. Per R6, the workops module does NOT store comment rows
 * inside the metadata-comment table — this is its own thread family
 * because the existing `metadata_comments` is metadata-keyed.
 */
@Serializable
data class TaskComment(
    val id: Long,
    @ColumnName("parent_id")
    val parentId: Long? = null,
    @ColumnName("task_id")
    @Contextual
    val taskId: UUID,
    @ColumnName("profile_id")
    @Contextual
    val profileId: UUID,
    @ColumnName("impersonator_id")
    @Contextual
    val impersonatorId: UUID? = null,
    val visibility: ProfileVisibility = ProfileVisibility.USER,
    val created: OffsetDateTime,
    val modified: OffsetDateTime,
    val status: CommentStatus,
    val content: String,
    val attributes: JsonElement? = null,
    @ColumnName("system_attributes")
    val systemAttributes: JsonElement? = null,
    @ColumnName("has_replies")
    val hasReplies: Boolean = false,
    val deleted: Boolean = false,
    val likes: Int = 0,
)

/** Input for creating a [TaskComment]. */
@Serializable
data class TaskCommentInput(
    val parentId: Long? = null,
    val visibility: ProfileVisibility = ProfileVisibility.USER,
    val content: String,
    val attributes: JsonElement? = null,
    val systemAttributes: JsonElement? = null,
)
