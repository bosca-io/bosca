package bosca.comments.service

import bosca.comments.CommentLikeNotFoundException
import bosca.comments.CommentNotFoundException
import bosca.comments.CommentThreadMismatchException
import bosca.comments.model.Comment
import bosca.comments.model.CommentInput
import bosca.comments.model.CommentStatus
import bosca.comments.model.CommentedMetadata
import bosca.comments.repository.CommentRepository
import bosca.db.transaction
import bosca.profile.model.ProfileVisibility
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.JsonElement

/**
 * Kotlin port of the Rust `CommentsDataStore`. The visibility logic
 * mirrors the Rust SQL exactly (see
 * `workspace/core/server/src/datastores/content/comments.rs`); we
 * just route to one of three pre-shaped repository methods per
 * call rather than building SQL strings at runtime.
 *
 * The like increment / decrement is wrapped in an explicit
 * `transaction { … }` so the denormalized counter and the
 * `metadata_comment_likes` row mutate atomically — exactly the
 * pattern the Rust impl uses with its `txn.commit() / txn.rollback()`
 * branching.
 */
@ServiceImplementation
class CommentServiceImpl(
    private val repository: CommentRepository,
) : CommentService {

    override suspend fun addMetadataComment(
        profileId: UUID,
        impersonatorId: UUID?,
        metadataId: UUID,
        version: Int,
        input: CommentInput,
    ): Long {
        // Reply parent must belong to the same (metadataId, version)
        // — the Rust impl checks this before inserting the row.
        val parentId = input.parentId
        if (parentId != null) {
            val parent = repository.getById(parentId)
                ?: throw CommentNotFoundException(parentId)
            if (parent.metadataId != metadataId || parent.version != version) {
                throw CommentThreadMismatchException(parentId, "does not belong to this thread")
            }
        }
        return repository.add(
            parentId = input.parentId,
            metadataId = metadataId,
            version = version,
            profileId = profileId,
            impersonatorId = impersonatorId,
            // The resolver pre-resolves visibility (manager override vs. profile
            // visibility); PUBLIC only guards a direct caller that left it null.
            visibility = input.visibility ?: ProfileVisibility.PUBLIC,
            content = input.content,
            attributes = input.attributes,
            systemAttributes = input.systemAttributes,
        )
    }

    override suspend fun addMetadataCommentLike(
        metadataId: UUID,
        version: Int,
        profileId: UUID,
        commentId: Long,
    ): Int = transaction {
        val likes = repository.incrementLikes(metadataId, version, commentId) ?: -1
        if (likes > 0) {
            repository.addLikeRow(commentId, profileId)
        }
        likes
    }

    override suspend fun deleteMetadataCommentLike(
        metadataId: UUID,
        version: Int,
        profileId: UUID,
        commentId: Long,
    ): Int = transaction {
        val likes = repository.decrementLikes(metadataId, version, commentId) ?: -1
        if (likes >= 0) {
            val deleted = repository.deleteLikeRow(commentId, profileId)
            if (deleted == null) {
                // Mirror the Rust rollback path when the like row didn't
                // exist: throwing rolls back the decrement above.
                throw CommentLikeNotFoundException(commentId, profileId)
            }
        }
        likes
    }

    override suspend fun getMetadataComment(
        profileId: UUID?,
        metadataId: UUID,
        version: Int,
        id: Long,
        manager: Boolean,
    ): Comment? = when {
        manager -> repository.getManager(metadataId, version, id)
        profileId != null -> repository.getForProfile(metadataId, version, id, profileId)
        else -> repository.getPublic(metadataId, version, id)
    }

    override suspend fun getMetadataCommentById(id: Long): Comment? = repository.getById(id)

    override suspend fun getMetadataComments(
        profileId: UUID?,
        metadataId: UUID,
        version: Int,
        manager: Boolean,
        offset: Long,
        limit: Long,
        pinnedOnly: Boolean,
    ): List<Comment> = when {
        manager -> repository.listManager(metadataId, version, offset, limit, pinnedOnly)
        profileId != null -> repository.listForProfile(metadataId, version, profileId, offset, limit, pinnedOnly)
        else -> repository.listPublic(metadataId, version, offset, limit, pinnedOnly)
    }

    override suspend fun getMetadataCommentsCount(
        profileId: UUID?,
        metadataId: UUID,
        version: Int,
        manager: Boolean,
        pinnedOnly: Boolean,
    ): Long = when {
        manager -> repository.countManager(metadataId, version, pinnedOnly)
        profileId != null -> repository.countForProfile(metadataId, version, profileId, pinnedOnly)
        else -> repository.countPublic(metadataId, version, pinnedOnly)
    }

    override suspend fun getMetadataCommentsByParentId(
        profileId: UUID?,
        metadataId: UUID,
        version: Int,
        parentId: Long,
        manager: Boolean,
        offset: Long,
        limit: Long,
    ): List<Comment> = when {
        manager -> repository.listRepliesManager(metadataId, version, parentId, offset, limit)
        profileId != null -> repository.listRepliesForProfile(metadataId, version, profileId, parentId, offset, limit)
        else -> repository.listRepliesPublic(metadataId, version, parentId, offset, limit)
    }

    override suspend fun getMetadataCommentsCountByParentId(
        profileId: UUID?,
        metadataId: UUID,
        version: Int,
        parentId: Long,
        manager: Boolean,
    ): Long = when {
        manager -> repository.countRepliesManager(metadataId, version, parentId)
        profileId != null -> repository.countRepliesForProfile(metadataId, version, profileId, parentId)
        else -> repository.countRepliesPublic(metadataId, version, parentId)
    }

    override suspend fun getMetadataCommentLikeIdsByProfile(profileId: UUID): List<Long> =
        repository.likeIdsByProfile(profileId).map { it.commentId }

    override suspend fun hasLiked(commentId: Long, profileId: UUID): Boolean =
        repository.likeExists(commentId, profileId)

    override suspend fun hasUnmoderatedComments(metadataId: UUID, version: Int): Boolean =
        repository.countUnmoderated(metadataId, version) > 0

    override suspend fun getCommentedMetadata(offset: Long, limit: Long): List<CommentedMetadata> =
        repository.listCommentedMetadata(offset, limit)

    override suspend fun getLastCommentAt(metadataId: UUID, version: Int): OffsetDateTime? =
        repository.lastCommentAt(metadataId, version)

    override suspend fun setMetadataCommentStatus(
        metadataId: UUID,
        version: Int,
        commentId: Long,
        status: CommentStatus,
    ) = repository.setStatus(metadataId, version, commentId, status)

    override suspend fun setMetadataCommentPinned(
        metadataId: UUID,
        version: Int,
        commentId: Long,
        pinned: Boolean,
    ) = repository.setPinned(metadataId, version, commentId, pinned)

    override suspend fun setMetadataCommentVisibility(
        metadataId: UUID,
        version: Int,
        commentId: Long,
        visibility: ProfileVisibility,
    ) = repository.setVisibility(metadataId, version, commentId, visibility)

    override suspend fun setMetadataCommentVisibilityByProfileId(
        metadataId: UUID,
        version: Int,
        commentId: Long,
        profileId: UUID,
        visibility: ProfileVisibility,
    ) = repository.setVisibilityByProfile(metadataId, version, commentId, profileId, visibility)

    override suspend fun setMetadataCommentAttributes(
        metadataId: UUID,
        version: Int,
        commentId: Long,
        attributes: JsonElement,
    ) = repository.setAttributes(metadataId, version, commentId, attributes)

    override suspend fun setMetadataCommentSystemAttributes(
        metadataId: UUID,
        version: Int,
        commentId: Long,
        systemAttributes: JsonElement,
    ) = repository.setSystemAttributes(metadataId, version, commentId, systemAttributes)

    override suspend fun mergeMetadataCommentSystemAttributes(
        metadataId: UUID,
        version: Int,
        commentId: Long,
        systemAttributes: JsonElement,
    ) = repository.mergeSystemAttributes(metadataId, version, commentId, systemAttributes)

    override suspend fun deleteMetadataComment(
        metadataId: UUID,
        version: Int,
        commentId: Long,
    ) = repository.softDelete(metadataId, version, commentId)

    override suspend fun deleteMetadataCommentByProfileId(
        metadataId: UUID,
        version: Int,
        commentId: Long,
        profileId: UUID,
    ) = repository.softDeleteByProfile(metadataId, version, commentId, profileId)
}
