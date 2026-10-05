package bosca.comments.graphql

import bosca.comments.events.CommentCreatedEvent
import bosca.comments.events.dispatch
import bosca.comments.model.Comment
import bosca.comments.model.CommentInput
import bosca.comments.model.CommentStatus
import bosca.comments.service.CommentService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.model.Profile
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

private const val COMMENT_DISABLED_ATTRIBUTE = "bosca.profiles.comment.disabled"

/**
 * Resolves `MetadataMutation.comments` ([CommentsMutation]). Each field takes
 * explicit `metadataId` / `metadataVersion` arguments. `setCommentStatus` /
 * `deleteComment` are the moderation decisions: a content MANAGER or the SA
 * group can approve/block/remove any comment; a regular user can only delete
 * their own.
 */
@TypeController
class CommentsMutationController(
    private val commentService: CommentService,
    private val profileService: ProfileService,
    private val metadataService: MetadataService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<CommentsMutation> {

    @Field
    suspend fun addComment(
        authentication: AuthenticationContext,
        comment: CommentInput,
        metadataId: UUID,
        metadataVersion: Int,
    ): Comment {
        val metadata = metadataService.getById(metadataId, metadataVersion) ?: error("Metadata not found")
        metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.VIEW)
        val isManager = metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.MANAGE)

        verifyCommentsEnabled(metadata, isManager)
        if (comment.parentId != null) {
            verifyRepliesEnabled(metadata, isManager)
        }

        val callerProfile = resolveProfile(authentication)
        verifyNotCommentDisabled(callerProfile)

        val authorProfile: Profile
        val impersonatorId: UUID?
        val impersonateId = comment.impersonateId
        if (impersonateId != null) {
            if (!isManager) throw SecurityException("impersonation requires MANAGE permission")
            authorProfile = profileService.getById(impersonateId)
            impersonatorId = callerProfile.id
        } else {
            authorProfile = callerProfile
            impersonatorId = null
        }

        // Use the visibility supplied on the input; when it's omitted (null) the
        // comment inherits the author's profile visibility (privacy default).
        val visibility = comment.visibility ?: authorProfile.visibility

        val commentId = commentService.addMetadataComment(
            profileId = authorProfile.id,
            impersonatorId = impersonatorId,
            metadataId = metadataId,
            version = metadataVersion,
            input = CommentInput(
                parentId = comment.parentId,
                visibility = visibility,
                content = comment.content,
                attributes = comment.attributes,
                systemAttributes = null,
                impersonateId = impersonateId,
            ),
        )

        CommentCreatedEvent(
            metadataId = metadataId,
            metadataVersion = metadataVersion,
            commentId = commentId,
            profileId = authorProfile.id,
        ).dispatch()

        return commentService.getMetadataComment(
            profileId = authorProfile.id,
            metadataId = metadataId,
            version = metadataVersion,
            id = commentId,
            manager = isManager,
        ) ?: error("Comment not found after creation")
    }

    @Field
    suspend fun addCommentLike(
        authentication: AuthenticationContext,
        commentId: Long,
        metadataId: UUID,
        metadataVersion: Int,
    ): Int {
        val metadata = metadataService.getById(metadataId, metadataVersion) ?: error("Metadata not found")
        metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.VIEW)
        val profile = resolveProfile(authentication)
        verifyNotCommentDisabled(profile)
        return commentService.addMetadataCommentLike(metadataId, metadataVersion, profile.id, commentId)
    }

    @Field
    suspend fun deleteCommentLike(
        authentication: AuthenticationContext,
        commentId: Long,
        metadataId: UUID,
        metadataVersion: Int,
    ): Int {
        val metadata = metadataService.getById(metadataId, metadataVersion) ?: error("Metadata not found")
        metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.VIEW)
        val profile = resolveProfile(authentication)
        verifyNotCommentDisabled(profile)
        return commentService.deleteMetadataCommentLike(metadataId, metadataVersion, profile.id, commentId)
    }

    @Field
    suspend fun deleteComment(
        authentication: AuthenticationContext,
        commentId: Long,
        metadataId: UUID,
        metadataVersion: Int,
    ): Boolean {
        val metadata = metadataService.getById(metadataId, metadataVersion) ?: error("Metadata not found")
        metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.VIEW)
        val profile = resolveProfile(authentication)
        val isManager = metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.MANAGE)
        if (isManager || groupEvaluator.hasSaGroup(authentication)) {
            commentService.deleteMetadataComment(metadataId, metadataVersion, commentId)
        } else {
            commentService.deleteMetadataCommentByProfileId(metadataId, metadataVersion, commentId, profile.id)
        }
        return true
    }

    @Field
    suspend fun setCommentStatus(
        authentication: AuthenticationContext,
        commentId: Long,
        metadataId: UUID,
        metadataVersion: Int,
        status: CommentStatus,
    ): Boolean {
        val metadata = metadataService.getById(metadataId, metadataVersion) ?: error("Metadata not found")
        if (!metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.MANAGE)) {
            groupEvaluator.verifyHasSaGroup(authentication)
        }
        commentService.setMetadataCommentStatus(metadataId, metadataVersion, commentId, status)
        return true
    }

    @Field
    suspend fun setCommentPinned(
        authentication: AuthenticationContext,
        commentId: Long,
        metadataId: UUID,
        metadataVersion: Int,
        pinned: Boolean,
    ): Boolean {
        val metadata = metadataService.getById(metadataId, metadataVersion) ?: error("Metadata not found")
        if (!metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.MANAGE)) {
            groupEvaluator.verifyHasSaGroup(authentication)
        }
        commentService.setMetadataCommentPinned(metadataId, metadataVersion, commentId, pinned)
        return true
    }

    @Field
    suspend fun setCommentVisibility(
        authentication: AuthenticationContext,
        commentId: Long,
        metadataId: UUID,
        metadataVersion: Int,
        visibility: ProfileVisibility,
    ): Boolean {
        val metadata = metadataService.getById(metadataId, metadataVersion) ?: error("Metadata not found")
        metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.VIEW)
        val profile = resolveProfile(authentication)
        val isManager = metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.MANAGE)
        if (isManager || groupEvaluator.hasSaGroup(authentication)) {
            commentService.setMetadataCommentVisibility(metadataId, metadataVersion, commentId, visibility)
        } else {
            commentService.setMetadataCommentVisibilityByProfileId(metadataId, metadataVersion, commentId, profile.id, visibility)
        }
        return true
    }

    @Field
    suspend fun setCommentAttributes(
        authentication: AuthenticationContext,
        commentId: Long,
        metadataId: UUID,
        metadataVersion: Int,
        attributes: JsonElement,
    ): Boolean {
        val metadata = metadataService.getById(metadataId, metadataVersion) ?: error("Metadata not found")
        metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.MANAGE)
        commentService.setMetadataCommentAttributes(metadataId, metadataVersion, commentId, attributes)
        return true
    }

    @Field
    suspend fun setCommentSystemAttributes(
        authentication: AuthenticationContext,
        commentId: Long,
        metadataId: UUID,
        metadataVersion: Int,
        attributes: JsonElement,
    ): Boolean {
        val metadata = metadataService.getById(metadataId, metadataVersion) ?: error("Metadata not found")
        metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.MANAGE)
        commentService.setMetadataCommentSystemAttributes(metadataId, metadataVersion, commentId, attributes)
        return true
    }

    @Field
    suspend fun mergeCommentSystemAttributes(
        authentication: AuthenticationContext,
        commentId: Long,
        metadataId: UUID,
        metadataVersion: Int,
        attributes: JsonElement,
    ): Boolean {
        val metadata = metadataService.getById(metadataId, metadataVersion) ?: error("Metadata not found")
        metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.MANAGE)
        commentService.mergeMetadataCommentSystemAttributes(metadataId, metadataVersion, commentId, attributes)
        return true
    }

    private fun verifyCommentsEnabled(metadata: Metadata, isManager: Boolean) {
        if (!metadata.commentsEnabled && !isManager) {
            throw SecurityException("comments are not enabled on this content")
        }
    }

    private fun verifyRepliesEnabled(metadata: Metadata, isManager: Boolean) {
        if (!metadata.commentRepliesEnabled && !isManager) {
            throw SecurityException("comment replies are not enabled on this content")
        }
    }

    private suspend fun verifyNotCommentDisabled(profile: Profile) {
        val attributes = profileService.getAttributes(profile.id)
        if (attributes.any { it.typeId == COMMENT_DISABLED_ATTRIBUTE }) {
            throw SecurityException("commenting is disabled for this profile")
        }
    }

    private suspend fun resolveProfile(authentication: AuthenticationContext): Profile {
        val principal = authentication.principal()?.asPrincipal() ?: throw SecurityException("not authenticated")
        return profileService.getPrimaryProfile(principal)
            ?: throw SecurityException("no profile found")
    }
}
