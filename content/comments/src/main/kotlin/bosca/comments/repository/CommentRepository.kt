package bosca.comments.repository

import bosca.comments.model.Comment
import bosca.comments.model.CommentStatus
import bosca.comments.model.CommentedMetadata
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.profile.model.ProfileVisibility
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

/**
 * Bosca's metadata-comment storage. Mirrors the Rust counterpart's
 * SQL in `workspace/core/server/src/datastores/content/comments.rs`
 * verbatim — the visibility / status filters, the like
 * denormalization, the `parent_id` predicate split, the soft-delete
 * column.
 *
 * The Rust side composes one of three SQL templates per call based
 * on the `manager` flag and whether a profile id is known. Bosca's
 * KSP `@Query` shape is one SQL string per method, so we expose the
 * same three shapes as three methods (suffixes `Manager`,
 * `ForProfile`, `Public`) and the service picks the right one.
 */
@Repository
interface CommentRepository {

    // -----------------------------------------------------------------
    // INSERT
    // -----------------------------------------------------------------

    @Query(
        """
        insert into metadata_comments
            (parent_id, metadata_id, version, profile_id, impersonator_id, visibility, content, attributes, system_attributes)
        values
            (:parentId, :metadataId, :version, :profileId, :impersonatorId, (:visibility)::profile_visibility, :content, :attributes::jsonb, :systemAttributes::jsonb)
        returning id
        """
    )
    suspend fun add(
        parentId: Long?,
        metadataId: UUID,
        version: Int,
        profileId: UUID,
        impersonatorId: UUID?,
        visibility: ProfileVisibility,
        content: String,
        attributes: JsonElement?,
        systemAttributes: JsonElement?,
    ): Long

    // -----------------------------------------------------------------
    // SINGLE READS
    // -----------------------------------------------------------------

    @Query("select * from metadata_comments where id = :id")
    suspend fun getById(id: Long): Comment?

    @Query(
        """
        select * from metadata_comments
        where metadata_id = :metadataId and version = :version and id = :id and deleted = false
        """
    )
    suspend fun getManager(metadataId: UUID, version: Int, id: Long): Comment?

    @Query(
        """
        select * from metadata_comments
        where metadata_id = :metadataId and version = :version and id = :id and deleted = false
          and ((visibility = 'public' and status = 'approved') or profile_id = :profileId)
        """
    )
    suspend fun getForProfile(metadataId: UUID, version: Int, id: Long, profileId: UUID): Comment?

    @Query(
        """
        select * from metadata_comments
        where metadata_id = :metadataId and version = :version and id = :id and deleted = false
          and visibility = 'public' and status = 'approved'
        """
    )
    suspend fun getPublic(metadataId: UUID, version: Int, id: Long): Comment?

    // -----------------------------------------------------------------
    // LIST: top-level comments
    // -----------------------------------------------------------------

    @Query(
        """
        select * from metadata_comments
        where metadata_id = :metadataId and version = :version and deleted = false
          and parent_id is null
          and (cast(:pinnedOnly as boolean) = false or pinned = true)
        order by created desc offset :offset limit :limit
        """
    )
    suspend fun listManager(metadataId: UUID, version: Int, offset: Long, limit: Long, pinnedOnly: Boolean): List<Comment>

    @Query(
        """
        select * from metadata_comments
        where metadata_id = :metadataId and version = :version and deleted = false
          and ((visibility = 'public' and status = 'approved') or profile_id = :profileId)
          and parent_id is null
          and (cast(:pinnedOnly as boolean) = false or pinned = true)
        order by created desc offset :offset limit :limit
        """
    )
    suspend fun listForProfile(
        metadataId: UUID,
        version: Int,
        profileId: UUID,
        offset: Long,
        limit: Long,
        pinnedOnly: Boolean,
    ): List<Comment>

    @Query(
        """
        select * from metadata_comments
        where metadata_id = :metadataId and version = :version and deleted = false
          and visibility = 'public' and status = 'approved'
          and parent_id is null
          and (cast(:pinnedOnly as boolean) = false or pinned = true)
        order by created desc offset :offset limit :limit
        """
    )
    suspend fun listPublic(metadataId: UUID, version: Int, offset: Long, limit: Long, pinnedOnly: Boolean): List<Comment>

    // -----------------------------------------------------------------
    // COUNT: top-level comments
    // -----------------------------------------------------------------

    @Query(
        """
        select count(*) from metadata_comments
        where metadata_id = :metadataId and version = :version and deleted = false
          and (cast(:pinnedOnly as boolean) = false or pinned = true)
        """
    )
    suspend fun countManager(metadataId: UUID, version: Int, pinnedOnly: Boolean): Long

    @Query(
        """
        select count(*) from metadata_comments
        where metadata_id = :metadataId and version = :version and deleted = false
          and ((visibility = 'public' and status = 'approved') or profile_id = :profileId)
          and (cast(:pinnedOnly as boolean) = false or pinned = true)
        """
    )
    suspend fun countForProfile(metadataId: UUID, version: Int, profileId: UUID, pinnedOnly: Boolean): Long

    @Query(
        """
        select count(*) from metadata_comments
        where metadata_id = :metadataId and version = :version and deleted = false
          and visibility = 'public' and status = 'approved'
          and (cast(:pinnedOnly as boolean) = false or pinned = true)
        """
    )
    suspend fun countPublic(metadataId: UUID, version: Int, pinnedOnly: Boolean): Long

    // -----------------------------------------------------------------
    // LIST: replies
    // -----------------------------------------------------------------

    @Query(
        """
        select * from metadata_comments
        where metadata_id = :metadataId and version = :version and deleted = false
          and parent_id = :parentId
        order by created desc offset :offset limit :limit
        """
    )
    suspend fun listRepliesManager(
        metadataId: UUID,
        version: Int,
        parentId: Long,
        offset: Long,
        limit: Long,
    ): List<Comment>

    @Query(
        """
        select * from metadata_comments
        where metadata_id = :metadataId and version = :version and deleted = false
          and ((visibility = 'public' and status = 'approved') or profile_id = :profileId)
          and parent_id = :parentId
        order by created desc offset :offset limit :limit
        """
    )
    suspend fun listRepliesForProfile(
        metadataId: UUID,
        version: Int,
        profileId: UUID,
        parentId: Long,
        offset: Long,
        limit: Long,
    ): List<Comment>

    @Query(
        """
        select * from metadata_comments
        where metadata_id = :metadataId and version = :version and deleted = false
          and visibility = 'public' and status = 'approved'
          and parent_id = :parentId
        order by created desc offset :offset limit :limit
        """
    )
    suspend fun listRepliesPublic(
        metadataId: UUID,
        version: Int,
        parentId: Long,
        offset: Long,
        limit: Long,
    ): List<Comment>

    @Query(
        """
        select count(*) from metadata_comments
        where metadata_id = :metadataId and version = :version and deleted = false
          and parent_id = :parentId
        """
    )
    suspend fun countRepliesManager(metadataId: UUID, version: Int, parentId: Long): Long

    @Query(
        """
        select count(*) from metadata_comments
        where metadata_id = :metadataId and version = :version and deleted = false
          and ((visibility = 'public' and status = 'approved') or profile_id = :profileId)
          and parent_id = :parentId
        """
    )
    suspend fun countRepliesForProfile(metadataId: UUID, version: Int, profileId: UUID, parentId: Long): Long

    @Query(
        """
        select count(*) from metadata_comments
        where metadata_id = :metadataId and version = :version and deleted = false
          and visibility = 'public' and status = 'approved'
          and parent_id = :parentId
        """
    )
    suspend fun countRepliesPublic(metadataId: UUID, version: Int, parentId: Long): Long

    // -----------------------------------------------------------------
    // LIKES
    // -----------------------------------------------------------------

    /** Increment the denormalized likes counter. Returns the new value, or -1 if no row matched. */
    @Query(
        """
        update metadata_comments
        set likes = likes + 1
        where metadata_id = :metadataId and version = :version and id = :commentId
        returning coalesce(likes, -1)
        """
    )
    suspend fun incrementLikes(metadataId: UUID, version: Int, commentId: Long): Int?

    @Query(
        """
        update metadata_comments
        set likes = likes - 1
        where metadata_id = :metadataId and version = :version and id = :commentId
        returning coalesce(likes, -1)
        """
    )
    suspend fun decrementLikes(metadataId: UUID, version: Int, commentId: Long): Int?

    @Query(
        """
        insert into metadata_comment_likes (comment_id, profile_id) values (:commentId, :profileId)
        """
    )
    suspend fun addLikeRow(commentId: Long, profileId: UUID)

    @Query(
        """
        delete from metadata_comment_likes
        where comment_id = :commentId and profile_id = :profileId
        returning comment_id
        """
    )
    suspend fun deleteLikeRow(commentId: Long, profileId: UUID): Long?

    @Query("select comment_id from metadata_comment_likes where profile_id = :profileId")
    suspend fun likeIdsByProfile(profileId: UUID): List<CommentLikeReference>

    /** Whether the given profile has liked the given comment. */
    @Query("select exists(select 1 from metadata_comment_likes where comment_id = :commentId and profile_id = :profileId)")
    suspend fun likeExists(commentId: Long, profileId: UUID): Boolean

    // -----------------------------------------------------------------
    // MODERATION: per-metadata "are there comments awaiting a decision?"
    // -----------------------------------------------------------------

    /** Count of comments on a metadata item still awaiting a decision (pending or pending-approval). */
    @Query(
        """
        select count(*) from metadata_comments
        where metadata_id = :metadataId and version = :version and deleted = false
          and status in ('pending', 'pending_approval')
        """
    )
    suspend fun countUnmoderated(metadataId: UUID, version: Int): Long

    /** Metadata items that have comments, most-recent-comment first — the "content with comments" list. */
    @Query(
        """
        select metadata_id, version from metadata_comments
        where deleted = false
        group by metadata_id, version
        order by max(created) desc
        offset :offset limit :limit
        """
    )
    suspend fun listCommentedMetadata(offset: Long, limit: Long): List<CommentedMetadata>

    /** Timestamp of the most recent comment on a metadata item, or null when it has none. */
    @Query(
        """
        select max(created) from metadata_comments
        where metadata_id = :metadataId and version = :version and deleted = false
        """
    )
    suspend fun lastCommentAt(metadataId: UUID, version: Int): OffsetDateTime?

    // -----------------------------------------------------------------
    // STATUS / ATTRIBUTES / DELETE
    // -----------------------------------------------------------------

    @Query(
        """
        update metadata_comments
        set status = (:status)::comment_status, modified = now()
        where metadata_id = :metadataId and version = :version and id = :commentId
        """
    )
    suspend fun setStatus(metadataId: UUID, version: Int, commentId: Long, status: CommentStatus)

    @Query(
        """
        update metadata_comments
        set pinned = :pinned, modified = now()
        where metadata_id = :metadataId and version = :version and id = :commentId
        """
    )
    suspend fun setPinned(metadataId: UUID, version: Int, commentId: Long, pinned: Boolean)

    @Query(
        """
        update metadata_comments
        set visibility = (:visibility)::profile_visibility, modified = now()
        where metadata_id = :metadataId and version = :version and id = :commentId
        """
    )
    suspend fun setVisibility(metadataId: UUID, version: Int, commentId: Long, visibility: ProfileVisibility)

    @Query(
        """
        update metadata_comments
        set visibility = (:visibility)::profile_visibility, modified = now()
        where metadata_id = :metadataId and version = :version and id = :commentId and profile_id = :profileId
        """
    )
    suspend fun setVisibilityByProfile(
        metadataId: UUID,
        version: Int,
        commentId: Long,
        profileId: UUID,
        visibility: ProfileVisibility,
    )

    @Query(
        """
        update metadata_comments
        set attributes = :attributes::jsonb, modified = now()
        where metadata_id = :metadataId and version = :version and id = :commentId
        """
    )
    suspend fun setAttributes(metadataId: UUID, version: Int, commentId: Long, attributes: JsonElement)

    @Query(
        """
        update metadata_comments
        set system_attributes = :systemAttributes::jsonb, modified = now()
        where metadata_id = :metadataId and version = :version and id = :commentId
        """
    )
    suspend fun setSystemAttributes(metadataId: UUID, version: Int, commentId: Long, systemAttributes: JsonElement)

    @Query(
        """
        update metadata_comments
        set system_attributes = coalesce(system_attributes, '{}'::jsonb) || :systemAttributes::jsonb, modified = now()
        where metadata_id = :metadataId and version = :version and id = :commentId
        """
    )
    suspend fun mergeSystemAttributes(metadataId: UUID, version: Int, commentId: Long, systemAttributes: JsonElement)

    @Query(
        """
        update metadata_comments
        set deleted = true
        where metadata_id = :metadataId and version = :version and id = :commentId
        """
    )
    suspend fun softDelete(metadataId: UUID, version: Int, commentId: Long)

    @Query(
        """
        update metadata_comments
        set deleted = true
        where metadata_id = :metadataId and version = :version and id = :commentId and profile_id = :profileId
        """
    )
    suspend fun softDeleteByProfile(metadataId: UUID, version: Int, commentId: Long, profileId: UUID)
}
