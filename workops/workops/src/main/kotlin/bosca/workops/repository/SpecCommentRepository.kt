package bosca.workops.repository

import bosca.comments.model.CommentStatus
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.profile.model.ProfileVisibility
import bosca.serialization.UUID
import bosca.workops.model.spec.SpecComment
import kotlinx.serialization.json.JsonElement

@Repository
interface SpecCommentRepository {

    @Query(
        """
        insert into workops.spec_comment
            (parent_id, spec_id, profile_id, impersonator_id, visibility, content, attributes, system_attributes)
        values
            (:parentId, :specId, :profileId, :impersonatorId, (:visibility)::workops.profile_visibility, :content, :attributes::jsonb, :systemAttributes::jsonb)
        returning id
        """
    )
    suspend fun add(
        parentId: Long?,
        specId: UUID,
        profileId: UUID,
        impersonatorId: UUID?,
        visibility: ProfileVisibility,
        content: String,
        attributes: JsonElement?,
        systemAttributes: JsonElement?,
    ): Long

    @Query("select * from workops.spec_comment where id = :id")
    suspend fun getById(id: Long): SpecComment?

    @Query(
        """
        select * from workops.spec_comment
        where spec_id = :specId and id = :id and deleted = false
        """
    )
    suspend fun getManager(specId: UUID, id: Long): SpecComment?

    @Query(
        """
        select * from workops.spec_comment
        where spec_id = :specId and id = :id and deleted = false
          and ((visibility = 'public' and status = 'approved') or profile_id = :profileId)
        """
    )
    suspend fun getForProfile(specId: UUID, id: Long, profileId: UUID): SpecComment?

    @Query(
        """
        select * from workops.spec_comment
        where spec_id = :specId and id = :id and deleted = false
          and visibility = 'public' and status = 'approved'
        """
    )
    suspend fun getPublic(specId: UUID, id: Long): SpecComment?

    @Query(
        """
        select * from workops.spec_comment
        where spec_id = :specId and deleted = false and status != 'blocked' and parent_id is null
        order by created desc offset :offset limit :limit
        """
    )
    suspend fun listManager(specId: UUID, offset: Long, limit: Long): List<SpecComment>

    @Query(
        """
        select * from workops.spec_comment
        where spec_id = :specId and deleted = false
          and ((visibility = 'public' and status = 'approved') or profile_id = :profileId)
          and parent_id is null
        order by created desc offset :offset limit :limit
        """
    )
    suspend fun listForProfile(specId: UUID, profileId: UUID, offset: Long, limit: Long): List<SpecComment>

    @Query(
        """
        select * from workops.spec_comment
        where spec_id = :specId and deleted = false
          and visibility = 'public' and status = 'approved'
          and parent_id is null
        order by created desc offset :offset limit :limit
        """
    )
    suspend fun listPublic(specId: UUID, offset: Long, limit: Long): List<SpecComment>

    @Query("select count(*) from workops.spec_comment where spec_id = :specId and deleted = false")
    suspend fun countManager(specId: UUID): Long

    @Query(
        """
        select count(*) from workops.spec_comment
        where spec_id = :specId and deleted = false
          and ((visibility = 'public' and status = 'approved') or profile_id = :profileId)
        """
    )
    suspend fun countForProfile(specId: UUID, profileId: UUID): Long

    @Query(
        """
        select count(*) from workops.spec_comment
        where spec_id = :specId and deleted = false
          and visibility = 'public' and status = 'approved'
        """
    )
    suspend fun countPublic(specId: UUID): Long

    @Query(
        """
        select * from workops.spec_comment
        where spec_id = :specId and deleted = false and status != 'blocked' and parent_id = :parentId
        order by created desc offset :offset limit :limit
        """
    )
    suspend fun listRepliesManager(specId: UUID, parentId: Long, offset: Long, limit: Long): List<SpecComment>

    @Query(
        """
        select * from workops.spec_comment
        where spec_id = :specId and deleted = false
          and ((visibility = 'public' and status = 'approved') or profile_id = :profileId)
          and parent_id = :parentId
        order by created desc offset :offset limit :limit
        """
    )
    suspend fun listRepliesForProfile(
        specId: UUID,
        profileId: UUID,
        parentId: Long,
        offset: Long,
        limit: Long,
    ): List<SpecComment>

    @Query(
        """
        select * from workops.spec_comment
        where spec_id = :specId and deleted = false
          and visibility = 'public' and status = 'approved'
          and parent_id = :parentId
        order by created desc offset :offset limit :limit
        """
    )
    suspend fun listRepliesPublic(specId: UUID, parentId: Long, offset: Long, limit: Long): List<SpecComment>

    @Query(
        """
        update workops.spec_comment
        set likes = likes + 1
        where spec_id = :specId and id = :commentId
        returning coalesce(likes, -1)
        """
    )
    suspend fun incrementLikes(specId: UUID, commentId: Long): Int?

    @Query(
        """
        update workops.spec_comment
        set likes = likes - 1
        where spec_id = :specId and id = :commentId
        returning coalesce(likes, -1)
        """
    )
    suspend fun decrementLikes(specId: UUID, commentId: Long): Int?

    @Query(
        """
        insert into workops.spec_comment_likes (comment_id, profile_id) values (:commentId, :profileId)
        on conflict (comment_id, profile_id) do nothing
        """
    )
    suspend fun addLikeRow(commentId: Long, profileId: UUID)

    @Query(
        """
        delete from workops.spec_comment_likes
        where comment_id = :commentId and profile_id = :profileId
        returning comment_id
        """
    )
    suspend fun deleteLikeRow(commentId: Long, profileId: UUID): Long?

    @Query(
        """
        update workops.spec_comment
        set status = (:status)::workops.comment_status, modified = now()
        where spec_id = :specId and id = :commentId
        """
    )
    suspend fun setStatus(specId: UUID, commentId: Long, status: CommentStatus)

    @Query(
        """
        update workops.spec_comment
        set deleted = true
        where spec_id = :specId and id = :commentId
        """
    )
    suspend fun softDelete(specId: UUID, commentId: Long)

    @Query(
        """
        update workops.spec_comment
        set deleted = true
        where spec_id = :specId and id = :commentId and profile_id = :profileId
        """
    )
    suspend fun softDeleteByProfile(specId: UUID, commentId: Long, profileId: UUID)
}
