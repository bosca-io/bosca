package bosca.comments.service

import bosca.comments.model.Comment
import bosca.comments.model.CommentInput
import bosca.comments.model.CommentStatus
import bosca.comments.model.CommentedMetadata
import bosca.profile.model.ProfileVisibility
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.Service
import kotlinx.serialization.json.JsonElement

/**
 * Comment subsystem for Bosca metadata. The Kotlin surface mirrors
 * the Rust `CommentsDataStore` in
 * `workspace/core/server/src/datastores/content/comments.rs` so the
 * two implementations behave identically — same status workflow,
 * same visibility filtering, same like denormalization.
 *
 * The `manager` parameter on the read paths is the moderator
 * escape: when true, the service returns rows in any non-`blocked`
 * status; when false, only `approved` public comments and
 * comments authored by the supplied profile come back. The Rust
 * implementation enforces this in SQL; Kotlin does the same.
 */
interface CommentService : Service {

    /**
     * Append a new comment to `(metadata_id, version)`. Replies set
     * `input.parentId` to a peer in the same thread; the service
     * verifies the parent belongs to the same metadata + version
     * before insert.
     *
     * @return the newly minted comment id (`bigserial`).
     */
    suspend fun addMetadataComment(
        profileId: UUID,
        impersonatorId: UUID?,
        metadataId: UUID,
        version: Int,
        input: CommentInput,
    ): Long

    /**
     * Increment the comment's likes counter and add a row to
     * `metadata_comment_likes` atomically. Returns the new likes
     * count, or `-1` when the underlying comment does not exist.
     */
    suspend fun addMetadataCommentLike(
        metadataId: UUID,
        version: Int,
        profileId: UUID,
        commentId: Long,
    ): Int

    /**
     * Decrement the comment's likes counter and remove the
     * corresponding `metadata_comment_likes` row. Returns the new
     * likes count, or `-1` when the underlying comment does not
     * exist.
     */
    suspend fun deleteMetadataCommentLike(
        metadataId: UUID,
        version: Int,
        profileId: UUID,
        commentId: Long,
    ): Int

    /**
     * Fetch a single comment by its full coordinates with visibility
     * filtering. `manager = true` skips the
     * `(visibility = 'public' and status = 'approved') or profile_id = $`
     * predicate.
     */
    suspend fun getMetadataComment(
        profileId: UUID?,
        metadataId: UUID,
        version: Int,
        id: Long,
        manager: Boolean,
    ): Comment?

    /** Fetch a comment by its primary key alone — used by reply parent checks. */
    suspend fun getMetadataCommentById(id: Long): Comment?

    /**
     * Top-level comments on `(metadata_id, version)`, pinned first then newest.
     * When [pinnedOnly] is true, only pinned comments are returned.
     */
    suspend fun getMetadataComments(
        profileId: UUID?,
        metadataId: UUID,
        version: Int,
        manager: Boolean,
        offset: Long,
        limit: Long,
        pinnedOnly: Boolean = false,
    ): List<Comment>

    /**
     * Top-level comment count on `(metadata_id, version)` after visibility
     * filtering. When [pinnedOnly] is true, counts only pinned comments.
     */
    suspend fun getMetadataCommentsCount(
        profileId: UUID?,
        metadataId: UUID,
        version: Int,
        manager: Boolean,
        pinnedOnly: Boolean = false,
    ): Long

    /** Replies to a specific parent comment, newest first. */
    suspend fun getMetadataCommentsByParentId(
        profileId: UUID?,
        metadataId: UUID,
        version: Int,
        parentId: Long,
        manager: Boolean,
        offset: Long,
        limit: Long,
    ): List<Comment>

    /** Reply count for a specific parent. */
    suspend fun getMetadataCommentsCountByParentId(
        profileId: UUID?,
        metadataId: UUID,
        version: Int,
        parentId: Long,
        manager: Boolean,
    ): Long

    /** Comment ids the given profile has liked. */
    suspend fun getMetadataCommentLikeIdsByProfile(profileId: UUID): List<Long>

    /** Whether the given profile has liked the given comment. */
    suspend fun hasLiked(commentId: Long, profileId: UUID): Boolean

    /**
     * Whether the given metadata has any comments still awaiting a moderation
     * decision (PENDING or PENDING_APPROVAL). Lets a content view flag items
     * that need a moderator's attention without paging the comments.
     */
    suspend fun hasUnmoderatedComments(metadataId: UUID, version: Int): Boolean

    /** Metadata items that have comments, most-recent-comment first (for the content-with-comments list). */
    suspend fun getCommentedMetadata(offset: Long, limit: Long): List<CommentedMetadata>

    /** The time of the most recent comment on a metadata item, or null when it has none. */
    suspend fun getLastCommentAt(metadataId: UUID, version: Int): OffsetDateTime?

    /** Moderator action: flip a comment's status. */
    suspend fun setMetadataCommentStatus(
        metadataId: UUID,
        version: Int,
        commentId: Long,
        status: CommentStatus,
    )

    /** Moderator action: pin or unpin a comment so it floats to the top of its thread. */
    suspend fun setMetadataCommentPinned(
        metadataId: UUID,
        version: Int,
        commentId: Long,
        pinned: Boolean,
    )

    /**
     * Change a comment's visibility scope. Setting it to `PUBLIC` (together with
     * an `APPROVED` status) makes the comment visible to everyone; any other
     * scope hides it from non-authors.
     */
    suspend fun setMetadataCommentVisibility(
        metadataId: UUID,
        version: Int,
        commentId: Long,
        visibility: ProfileVisibility,
    )

    /**
     * Change a comment's visibility scope only when the supplied profile is its
     * author — the self-service path for a user managing their own comment.
     */
    suspend fun setMetadataCommentVisibilityByProfileId(
        metadataId: UUID,
        version: Int,
        commentId: Long,
        profileId: UUID,
        visibility: ProfileVisibility,
    )

    /** Replace the user-facing `attributes` JSON. */
    suspend fun setMetadataCommentAttributes(
        metadataId: UUID,
        version: Int,
        commentId: Long,
        attributes: JsonElement,
    )

    /** Replace the moderator-only `system_attributes` JSON. */
    suspend fun setMetadataCommentSystemAttributes(
        metadataId: UUID,
        version: Int,
        commentId: Long,
        systemAttributes: JsonElement,
    )

    /** Merge new keys into the moderator-only `system_attributes` JSON. */
    suspend fun mergeMetadataCommentSystemAttributes(
        metadataId: UUID,
        version: Int,
        commentId: Long,
        systemAttributes: JsonElement,
    )

    /** Soft-delete a comment regardless of author. */
    suspend fun deleteMetadataComment(
        metadataId: UUID,
        version: Int,
        commentId: Long,
    )

    /** Soft-delete a comment only when the supplied profile is its author. */
    suspend fun deleteMetadataCommentByProfileId(
        metadataId: UUID,
        version: Int,
        commentId: Long,
        profileId: UUID,
    )
}
