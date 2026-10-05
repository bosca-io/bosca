package bosca.workops.repository

import bosca.comments.model.CommentStatus
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.profile.model.ProfileVisibility
import bosca.serialization.UUID
import bosca.workops.model.requirement.RequirementComment
import kotlinx.serialization.json.JsonElement

@Repository
interface RequirementCommentRepository {

    @Query(
        """
        insert into workops.requirement_comment
            (parent_id, requirement_id, profile_id, impersonator_id, visibility, content, attributes, system_attributes)
        values
            (:parentId, :requirementId, :profileId, :impersonatorId, (:visibility)::workops.profile_visibility, :content, :attributes::jsonb, :systemAttributes::jsonb)
        returning id
        """
    )
    suspend fun add(
        parentId: Long?,
        requirementId: UUID,
        profileId: UUID,
        impersonatorId: UUID?,
        visibility: ProfileVisibility,
        content: String,
        attributes: JsonElement?,
        systemAttributes: JsonElement?,
    ): Long

    @Query("select * from workops.requirement_comment where id = :id")
    suspend fun getById(id: Long): RequirementComment?

    @Query(
        """
        select * from workops.requirement_comment
        where requirement_id = :requirementId and id = :id and deleted = false
        """
    )
    suspend fun getManager(requirementId: UUID, id: Long): RequirementComment?

    @Query(
        """
        select * from workops.requirement_comment
        where requirement_id = :requirementId and id = :id and deleted = false
          and ((visibility = 'public' and status = 'approved') or profile_id = :profileId)
        """
    )
    suspend fun getForProfile(requirementId: UUID, id: Long, profileId: UUID): RequirementComment?

    @Query(
        """
        select * from workops.requirement_comment
        where requirement_id = :requirementId and id = :id and deleted = false
          and visibility = 'public' and status = 'approved'
        """
    )
    suspend fun getPublic(requirementId: UUID, id: Long): RequirementComment?

    @Query(
        """
        select * from workops.requirement_comment
        where requirement_id = :requirementId and deleted = false and status != 'blocked' and parent_id is null
        order by created desc offset :offset limit :limit
        """
    )
    suspend fun listManager(requirementId: UUID, offset: Long, limit: Long): List<RequirementComment>

    @Query(
        """
        select * from workops.requirement_comment
        where requirement_id = :requirementId and deleted = false
          and ((visibility = 'public' and status = 'approved') or profile_id = :profileId)
          and parent_id is null
        order by created desc offset :offset limit :limit
        """
    )
    suspend fun listForProfile(requirementId: UUID, profileId: UUID, offset: Long, limit: Long): List<RequirementComment>

    @Query(
        """
        select * from workops.requirement_comment
        where requirement_id = :requirementId and deleted = false
          and visibility = 'public' and status = 'approved'
          and parent_id is null
        order by created desc offset :offset limit :limit
        """
    )
    suspend fun listPublic(requirementId: UUID, offset: Long, limit: Long): List<RequirementComment>

    @Query("select count(*) from workops.requirement_comment where requirement_id = :requirementId and deleted = false")
    suspend fun countManager(requirementId: UUID): Long

    @Query(
        """
        select count(*) from workops.requirement_comment
        where requirement_id = :requirementId and deleted = false
          and ((visibility = 'public' and status = 'approved') or profile_id = :profileId)
        """
    )
    suspend fun countForProfile(requirementId: UUID, profileId: UUID): Long

    @Query(
        """
        select count(*) from workops.requirement_comment
        where requirement_id = :requirementId and deleted = false
          and visibility = 'public' and status = 'approved'
        """
    )
    suspend fun countPublic(requirementId: UUID): Long

    @Query(
        """
        select * from workops.requirement_comment
        where requirement_id = :requirementId and deleted = false and status != 'blocked' and parent_id = :parentId
        order by created desc offset :offset limit :limit
        """
    )
    suspend fun listRepliesManager(requirementId: UUID, parentId: Long, offset: Long, limit: Long): List<RequirementComment>

    @Query(
        """
        select * from workops.requirement_comment
        where requirement_id = :requirementId and deleted = false
          and ((visibility = 'public' and status = 'approved') or profile_id = :profileId)
          and parent_id = :parentId
        order by created desc offset :offset limit :limit
        """
    )
    suspend fun listRepliesForProfile(
        requirementId: UUID,
        profileId: UUID,
        parentId: Long,
        offset: Long,
        limit: Long,
    ): List<RequirementComment>

    @Query(
        """
        select * from workops.requirement_comment
        where requirement_id = :requirementId and deleted = false
          and visibility = 'public' and status = 'approved'
          and parent_id = :parentId
        order by created desc offset :offset limit :limit
        """
    )
    suspend fun listRepliesPublic(requirementId: UUID, parentId: Long, offset: Long, limit: Long): List<RequirementComment>

    @Query(
        """
        update workops.requirement_comment
        set likes = likes + 1
        where requirement_id = :requirementId and id = :commentId
        returning coalesce(likes, -1)
        """
    )
    suspend fun incrementLikes(requirementId: UUID, commentId: Long): Int?

    @Query(
        """
        update workops.requirement_comment
        set likes = likes - 1
        where requirement_id = :requirementId and id = :commentId
        returning coalesce(likes, -1)
        """
    )
    suspend fun decrementLikes(requirementId: UUID, commentId: Long): Int?

    @Query(
        """
        insert into workops.requirement_comment_likes (comment_id, profile_id) values (:commentId, :profileId)
        on conflict (comment_id, profile_id) do nothing
        """
    )
    suspend fun addLikeRow(commentId: Long, profileId: UUID)

    @Query(
        """
        delete from workops.requirement_comment_likes
        where comment_id = :commentId and profile_id = :profileId
        returning comment_id
        """
    )
    suspend fun deleteLikeRow(commentId: Long, profileId: UUID): Long?

    @Query(
        """
        update workops.requirement_comment
        set status = (:status)::workops.comment_status, modified = now()
        where requirement_id = :requirementId and id = :commentId
        """
    )
    suspend fun setStatus(requirementId: UUID, commentId: Long, status: CommentStatus)

    @Query(
        """
        update workops.requirement_comment
        set deleted = true
        where requirement_id = :requirementId and id = :commentId
        """
    )
    suspend fun softDelete(requirementId: UUID, commentId: Long)

    @Query(
        """
        update workops.requirement_comment
        set deleted = true
        where requirement_id = :requirementId and id = :commentId and profile_id = :profileId
        """
    )
    suspend fun softDeleteByProfile(requirementId: UUID, commentId: Long, profileId: UUID)
}
