package bosca.workops.service

import bosca.comments.model.CommentStatus
import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.audit.TaskHistoryEntry
import bosca.workops.model.comment.TaskComment
import bosca.workops.model.comment.TaskCommentInput

/**
 * Comment thread service for tasks (R6). Mirrors the Rust
 * `CommentsDataStore` shape — manager / for-profile / public
 * visibility branches, denormalized likes counter, soft delete —
 * but keys on `task_id` instead of `(metadata_id, version)`.
 *
 * Per R6, every comment mutation also writes a [TaskHistoryEntry]
 * in the same transaction so the audit log threads comment events
 * alongside field changes. The acting principal id and (optional)
 * profile id come from the calling controller; the service carries
 * no auth context of its own.
 */
interface TaskCommentService : Service {

    suspend fun add(
        taskId: UUID,
        input: TaskCommentInput,
        actingPrincipalId: UUID,
        actingProfileId: UUID,
        impersonatorId: UUID? = null,
    ): TaskComment

    suspend fun get(
        taskId: UUID,
        commentId: Long,
        viewingProfileId: UUID?,
        manager: Boolean,
    ): TaskComment?

    suspend fun list(
        taskId: UUID,
        viewingProfileId: UUID?,
        manager: Boolean,
        offset: Long,
        limit: Long,
    ): List<TaskComment>

    suspend fun count(taskId: UUID, viewingProfileId: UUID?, manager: Boolean): Long

    suspend fun listReplies(
        taskId: UUID,
        parentId: Long,
        viewingProfileId: UUID?,
        manager: Boolean,
        offset: Long,
        limit: Long,
    ): List<TaskComment>

    suspend fun like(taskId: UUID, commentId: Long, profileId: UUID): Int

    suspend fun unlike(taskId: UUID, commentId: Long, profileId: UUID): Int

    suspend fun setStatus(
        taskId: UUID,
        commentId: Long,
        status: CommentStatus,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    )

    suspend fun delete(
        taskId: UUID,
        commentId: Long,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    )

    suspend fun deleteByAuthor(
        taskId: UUID,
        commentId: Long,
        profileId: UUID,
        actingPrincipalId: UUID,
    )
}
