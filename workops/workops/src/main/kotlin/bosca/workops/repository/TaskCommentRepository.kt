package bosca.workops.repository

import bosca.comments.model.CommentStatus
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.profile.model.ProfileVisibility
import bosca.serialization.UUID
import bosca.workops.model.comment.TaskComment
import kotlinx.serialization.json.JsonElement

/**
 * Persists [TaskComment] rows in `workops.task_comment`. Mirrors the
 * Rust metadata-comment SQL pattern — visibility filtering happens in
 * the WHERE clause, replies are split out by `parent_id is null`,
 * likes are tracked both as a denormalized counter and as
 * `task_comment_likes` rows for the per-profile "I liked this" UI.
 *
 * The single-version branching the Rust impl uses (manager / for-
 * profile / public) lands here as three method shapes per query;
 * the service picks the right one given the caller's auth context.
 */
@Repository
interface TaskCommentRepository {

    @Query(
        """
        insert into workops.task_comment
            (parent_id, task_id, profile_id, impersonator_id, visibility, content, attributes, system_attributes)
        values
            (:parentId, :taskId, :profileId, :impersonatorId, (:visibility)::workops.profile_visibility, :content, :attributes::jsonb, :systemAttributes::jsonb)
        returning id
        """
    )
    suspend fun add(
        parentId: Long?,
        taskId: UUID,
        profileId: UUID,
        impersonatorId: UUID?,
        visibility: ProfileVisibility,
        content: String,
        attributes: JsonElement?,
        systemAttributes: JsonElement?,
    ): Long

    @Query("select * from workops.task_comment where id = :id")
    suspend fun getById(id: Long): TaskComment?

    @Query(
        """
        select * from workops.task_comment
        where task_id = :taskId and id = :id and deleted = false
        """
    )
    suspend fun getManager(taskId: UUID, id: Long): TaskComment?

    @Query(
        """
        select * from workops.task_comment
        where task_id = :taskId and id = :id and deleted = false
          and ((visibility = 'public' and status = 'approved') or profile_id = :profileId)
        """
    )
    suspend fun getForProfile(taskId: UUID, id: Long, profileId: UUID): TaskComment?

    @Query(
        """
        select * from workops.task_comment
        where task_id = :taskId and id = :id and deleted = false
          and visibility = 'public' and status = 'approved'
        """
    )
    suspend fun getPublic(taskId: UUID, id: Long): TaskComment?

    @Query(
        """
        select * from workops.task_comment
        where task_id = :taskId and deleted = false and status != 'blocked' and parent_id is null
        order by created desc offset :offset limit :limit
        """
    )
    suspend fun listManager(taskId: UUID, offset: Long, limit: Long): List<TaskComment>

    @Query(
        """
        select * from workops.task_comment
        where task_id = :taskId and deleted = false
          and ((visibility = 'public' and status = 'approved') or profile_id = :profileId)
          and parent_id is null
        order by created desc offset :offset limit :limit
        """
    )
    suspend fun listForProfile(taskId: UUID, profileId: UUID, offset: Long, limit: Long): List<TaskComment>

    @Query(
        """
        select * from workops.task_comment
        where task_id = :taskId and deleted = false
          and visibility = 'public' and status = 'approved'
          and parent_id is null
        order by created desc offset :offset limit :limit
        """
    )
    suspend fun listPublic(taskId: UUID, offset: Long, limit: Long): List<TaskComment>

    @Query(
        """
        select count(*) from workops.task_comment
        where task_id = :taskId and deleted = false
        """
    )
    suspend fun countManager(taskId: UUID): Long

    @Query(
        """
        select count(*) from workops.task_comment
        where task_id = :taskId and deleted = false
          and ((visibility = 'public' and status = 'approved') or profile_id = :profileId)
        """
    )
    suspend fun countForProfile(taskId: UUID, profileId: UUID): Long

    @Query(
        """
        select count(*) from workops.task_comment
        where task_id = :taskId and deleted = false
          and visibility = 'public' and status = 'approved'
        """
    )
    suspend fun countPublic(taskId: UUID): Long

    @Query(
        """
        select * from workops.task_comment
        where task_id = :taskId and deleted = false and status != 'blocked' and parent_id = :parentId
        order by created desc offset :offset limit :limit
        """
    )
    suspend fun listRepliesManager(taskId: UUID, parentId: Long, offset: Long, limit: Long): List<TaskComment>

    @Query(
        """
        select * from workops.task_comment
        where task_id = :taskId and deleted = false
          and ((visibility = 'public' and status = 'approved') or profile_id = :profileId)
          and parent_id = :parentId
        order by created desc offset :offset limit :limit
        """
    )
    suspend fun listRepliesForProfile(
        taskId: UUID,
        profileId: UUID,
        parentId: Long,
        offset: Long,
        limit: Long,
    ): List<TaskComment>

    @Query(
        """
        select * from workops.task_comment
        where task_id = :taskId and deleted = false
          and visibility = 'public' and status = 'approved'
          and parent_id = :parentId
        order by created desc offset :offset limit :limit
        """
    )
    suspend fun listRepliesPublic(taskId: UUID, parentId: Long, offset: Long, limit: Long): List<TaskComment>

    @Query(
        """
        update workops.task_comment
        set likes = likes + 1
        where task_id = :taskId and id = :commentId
        returning coalesce(likes, -1)
        """
    )
    suspend fun incrementLikes(taskId: UUID, commentId: Long): Int?

    @Query(
        """
        update workops.task_comment
        set likes = likes - 1
        where task_id = :taskId and id = :commentId
        returning coalesce(likes, -1)
        """
    )
    suspend fun decrementLikes(taskId: UUID, commentId: Long): Int?

    @Query(
        """
        insert into workops.task_comment_likes (comment_id, profile_id) values (:commentId, :profileId)
        on conflict (comment_id, profile_id) do nothing
        """
    )
    suspend fun addLikeRow(commentId: Long, profileId: UUID)

    @Query(
        """
        delete from workops.task_comment_likes
        where comment_id = :commentId and profile_id = :profileId
        returning comment_id
        """
    )
    suspend fun deleteLikeRow(commentId: Long, profileId: UUID): Long?

    @Query(
        """
        update workops.task_comment
        set status = (:status)::workops.comment_status, modified = now()
        where task_id = :taskId and id = :commentId
        """
    )
    suspend fun setStatus(taskId: UUID, commentId: Long, status: CommentStatus)

    @Query(
        """
        update workops.task_comment
        set deleted = true
        where task_id = :taskId and id = :commentId
        """
    )
    suspend fun softDelete(taskId: UUID, commentId: Long)

    @Query(
        """
        update workops.task_comment
        set deleted = true
        where task_id = :taskId and id = :commentId and profile_id = :profileId
        """
    )
    suspend fun softDeleteByProfile(taskId: UUID, commentId: Long, profileId: UUID)
}
