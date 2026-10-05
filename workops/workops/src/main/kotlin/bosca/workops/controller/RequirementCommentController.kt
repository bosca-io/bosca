package bosca.workops.controller

import bosca.comments.model.CommentStatus
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.requirement.RequirementComment
import bosca.workops.model.requirement.RequirementCommentInput
import bosca.workops.repository.RequirementCommentRepository
import bosca.workops.service.RequirementCommentService
import bosca.workops.service.RequirementPermissionEvaluator
import bosca.workops.service.RequirementService

@TypeController(type = "WorkOpsRequirementComment")
class RequirementCommentTypeFieldController(
    private val commentService: RequirementCommentService,
    private val commentRepository: RequirementCommentRepository,
    private val requirementService: RequirementService,
    private val requirementPermissions: RequirementPermissionEvaluator,
    private val profileService: ProfileService,
    private val profilePermissions: ProfilePermissionEvaluator,
) : GraphQLController<RequirementComment> {

    @Field fun id(c: RequirementComment) = c.id
    @Field fun parentId(c: RequirementComment) = c.parentId
    @Field fun requirementId(c: RequirementComment) = c.requirementId
    @Field fun profileId(c: RequirementComment) = c.profileId
    @Field fun impersonatorId(c: RequirementComment) = c.impersonatorId
    @Field fun visibility(c: RequirementComment) = c.visibility
    @Field fun created(c: RequirementComment) = c.created
    @Field fun modified(c: RequirementComment) = c.modified
    @Field fun status(c: RequirementComment) = c.status
    @Field fun content(c: RequirementComment) = c.content
    @Field fun likes(c: RequirementComment) = c.likes
    @Field fun deleted(c: RequirementComment) = c.deleted

    @Field
    suspend fun profile(authentication: AuthenticationContext, c: RequirementComment): Profile? {
        val profile = profileService.getById(c.profileId)
        return if (profilePermissions.isAllowed(authentication, profile, PermissionAction.VIEW)) profile else null
    }

    private suspend fun resolveViewingProfileId(authentication: AuthenticationContext?): UUID? {
        val context = authentication ?: return null
        val authenticated = context.principal() ?: return null
        val principal = authenticated.asPrincipal()
        val primaryProfileId = principal.primaryProfileId
        if (primaryProfileId != null) return primaryProfileId
        val profile = profileService.getByPrincipal(principal.id).firstOrNull() ?: return null
        return profile.id
    }

    @Field
    suspend fun replies(
        c: RequirementComment,
        authentication: AuthenticationContext?,
        offset: Long,
        limit: Long,
    ): List<RequirementComment> {
        val requirement = requirementService.getById(c.requirementId)
        if (requirement == null || authentication == null ||
            !requirementPermissions.isAllowed(authentication, requirement, PermissionAction.VIEW)) {
            return commentRepository.listRepliesPublic(c.requirementId, c.id, offset, limit)
        }
        val manager = requirementPermissions.isAllowed(authentication, requirement, PermissionAction.MANAGE)
        if (manager) {
            return commentRepository.listRepliesManager(c.requirementId, c.id, offset, limit)
        }
        val viewingProfileId = resolveViewingProfileId(authentication)
        return if (viewingProfileId != null) {
            commentRepository.listRepliesForProfile(c.requirementId, viewingProfileId, c.id, offset, limit)
        } else {
            commentRepository.listRepliesPublic(c.requirementId, c.id, offset, limit)
        }
    }
}

object WorkOpsRequirementComments

@TypeController
class RequirementCommentQueryController(
    private val commentService: RequirementCommentService,
    private val requirementService: RequirementService,
    private val requirementPermissions: RequirementPermissionEvaluator,
    private val profileService: ProfileService,
) : GraphQLController<WorkOpsRequirementComments> {

    private suspend fun resolveViewingProfileId(authentication: AuthenticationContext): UUID? {
        val authenticated = authentication.principal() ?: return null
        val principal = authenticated.asPrincipal()
        val primaryProfileId = principal.primaryProfileId
        if (primaryProfileId != null) return primaryProfileId
        val profile = profileService.getByPrincipal(principal.id).firstOrNull() ?: return null
        return profile.id
    }

    @Field
    suspend fun forRequirement(authentication: AuthenticationContext, requirementId: UUID, offset: Long, limit: Long): List<RequirementComment> {
        val requirement = requirementService.getById(requirementId) ?: return emptyList()
        if (!requirementPermissions.isAllowed(authentication, requirement, PermissionAction.VIEW)) return emptyList()
        if (requirementPermissions.isAllowed(authentication, requirement, PermissionAction.MANAGE)) {
            return commentService.listManager(requirementId, offset, limit)
        }
        val viewingProfileId = resolveViewingProfileId(authentication)
        return if (viewingProfileId != null) {
            commentService.listForProfile(requirementId, viewingProfileId, offset, limit)
        } else {
            commentService.listPublic(requirementId, offset, limit)
        }
    }

    @Field
    suspend fun countForRequirement(authentication: AuthenticationContext, requirementId: UUID): Long {
        val requirement = requirementService.getById(requirementId) ?: return 0
        if (!requirementPermissions.isAllowed(authentication, requirement, PermissionAction.VIEW)) return 0
        return commentService.countManager(requirementId)
    }

    @Field
    suspend fun comment(authentication: AuthenticationContext, requirementId: UUID, commentId: Long): RequirementComment? {
        val requirement = requirementService.getById(requirementId) ?: return null
        if (!requirementPermissions.isAllowed(authentication, requirement, PermissionAction.VIEW)) return null
        if (requirementPermissions.isAllowed(authentication, requirement, PermissionAction.MANAGE)) {
            return commentService.getManager(requirementId, commentId)
        }
        val viewingProfileId = resolveViewingProfileId(authentication)
        return if (viewingProfileId != null) {
            commentService.getForProfile(requirementId, commentId, viewingProfileId)
        } else {
            commentService.getPublic(requirementId, commentId)
        }
    }
}

object WorkOpsRequirementCommentsMutation

@TypeController
class RequirementCommentMutationController(
    private val commentService: RequirementCommentService,
    private val requirementService: RequirementService,
    private val requirementPermissions: RequirementPermissionEvaluator,
    private val profileService: ProfileService,
) : GraphQLController<WorkOpsRequirementCommentsMutation> {

    private suspend fun resolveProfileId(authentication: AuthenticationContext, operation: String): UUID {
        val authenticated = authentication.principal()
            ?: error("$operation requires an authenticated principal")
        val principal = authenticated.asPrincipal()
        val primaryProfileId = principal.primaryProfileId
        if (primaryProfileId != null) return primaryProfileId
        val profile = profileService.getByPrincipal(principal.id).firstOrNull()
            ?: error("$operation requires the principal to have a profile")
        return profile.id
    }

    @Field
    suspend fun add(authentication: AuthenticationContext, requirementId: UUID, input: RequirementCommentInput): RequirementComment {
        val requirement = requirementService.getById(requirementId) ?: error("addRequirementComment: requirement $requirementId not found")
        requirementPermissions.verifyAllowed(authentication, requirement, PermissionAction.EDIT)
        val authenticated = authentication.principal()
            ?: error("addRequirementComment requires an authenticated principal")
        val profileId = resolveProfileId(authentication, "addRequirementComment")
        return commentService.add(requirementId, input, authenticated.id, profileId)
    }

    @Field
    suspend fun like(authentication: AuthenticationContext, requirementId: UUID, commentId: Long): Int {
        val requirement = requirementService.getById(requirementId) ?: error("likeRequirementComment: requirement $requirementId not found")
        requirementPermissions.verifyAllowed(authentication, requirement, PermissionAction.VIEW)
        val profileId = resolveProfileId(authentication, "likeRequirementComment")
        commentService.like(requirementId, commentId, profileId)
        return commentService.getManager(requirementId, commentId)?.likes ?: -1
    }

    @Field
    suspend fun unlike(authentication: AuthenticationContext, requirementId: UUID, commentId: Long): Int {
        val requirement = requirementService.getById(requirementId) ?: error("unlikeRequirementComment: requirement $requirementId not found")
        requirementPermissions.verifyAllowed(authentication, requirement, PermissionAction.VIEW)
        val profileId = resolveProfileId(authentication, "unlikeRequirementComment")
        commentService.unlike(requirementId, commentId, profileId)
        return commentService.getManager(requirementId, commentId)?.likes ?: -1
    }

    @Field
    suspend fun setStatus(authentication: AuthenticationContext, requirementId: UUID, commentId: Long, status: CommentStatus): Boolean {
        val requirement = requirementService.getById(requirementId) ?: error("setRequirementCommentStatus: requirement $requirementId not found")
        requirementPermissions.verifyAllowed(authentication, requirement, PermissionAction.MANAGE)
        val authenticated = authentication.principal()
            ?: error("setRequirementCommentStatus requires an authenticated principal")
        val profileId = resolveProfileId(authentication, "setRequirementCommentStatus")
        commentService.setStatus(requirementId, commentId, status, authenticated.id, profileId)
        return true
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, requirementId: UUID, commentId: Long): Boolean {
        val requirement = requirementService.getById(requirementId) ?: error("deleteRequirementComment: requirement $requirementId not found")
        requirementPermissions.verifyAllowed(authentication, requirement, PermissionAction.MANAGE)
        val authenticated = authentication.principal()
            ?: error("deleteRequirementComment requires an authenticated principal")
        val profileId = resolveProfileId(authentication, "deleteRequirementComment")
        commentService.delete(requirementId, commentId, authenticated.id, profileId)
        return true
    }
}
