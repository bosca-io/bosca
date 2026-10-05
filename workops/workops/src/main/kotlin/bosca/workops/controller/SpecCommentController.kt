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
import bosca.workops.model.spec.SpecComment
import bosca.workops.model.spec.SpecCommentInput
import bosca.workops.repository.SpecCommentRepository
import bosca.workops.service.SpecCommentService
import bosca.workops.service.SpecPermissionEvaluator
import bosca.workops.service.SpecService

@TypeController(type = "WorkOpsSpecComment")
class SpecCommentTypeFieldController(
    private val commentService: SpecCommentService,
    private val commentRepository: SpecCommentRepository,
    private val specService: SpecService,
    private val specPermissions: SpecPermissionEvaluator,
    private val profileService: ProfileService,
    private val profilePermissions: ProfilePermissionEvaluator,
) : GraphQLController<SpecComment> {

    @Field
    fun id(c: SpecComment) = c.id

    @Field
    fun parentId(c: SpecComment) = c.parentId

    @Field
    fun specId(c: SpecComment) = c.specId

    @Field
    fun profileId(c: SpecComment) = c.profileId

    @Field
    fun impersonatorId(c: SpecComment) = c.impersonatorId

    @Field
    fun visibility(c: SpecComment) = c.visibility

    @Field
    fun created(c: SpecComment) = c.created

    @Field
    fun modified(c: SpecComment) = c.modified

    @Field
    fun status(c: SpecComment) = c.status

    @Field
    fun content(c: SpecComment) = c.content

    @Field
    fun likes(c: SpecComment) = c.likes

    @Field
    fun deleted(c: SpecComment) = c.deleted

    @Field
    suspend fun profile(authentication: AuthenticationContext, c: SpecComment): Profile? {
        val profile = profileService.getById(c.profileId)
        return if (profilePermissions.isAllowed(authentication, profile, PermissionAction.VIEW)) profile else null
    }

    private suspend fun resolveViewingProfileId(authentication: AuthenticationContext): UUID? {
        val authenticated = authentication.principal() ?: return null
        val principal = authenticated.asPrincipal()
        return principal.primaryProfileId
            ?: profileService.getByPrincipal(principal.id).firstOrNull()?.id
    }

    @Field
    suspend fun replies(
        c: SpecComment,
        authentication: AuthenticationContext?,
        offset: Long,
        limit: Long,
    ): List<SpecComment> {
        val spec = specService.getById(c.specId)
        if (spec == null || authentication == null ||
            !specPermissions.isAllowed(authentication, spec, PermissionAction.VIEW)
        ) {
            return commentRepository.listRepliesPublic(c.specId, c.id, offset, limit)
        }
        val manager = specPermissions.isAllowed(authentication, spec, PermissionAction.MANAGE)
        if (manager) {
            return commentRepository.listRepliesManager(c.specId, c.id, offset, limit)
        }
        val viewingProfileId = resolveViewingProfileId(authentication)
        return if (viewingProfileId != null) {
            commentRepository.listRepliesForProfile(c.specId, viewingProfileId, c.id, offset, limit)
        } else {
            commentRepository.listRepliesPublic(c.specId, c.id, offset, limit)
        }
    }
}

object WorkOpsSpecComments

@TypeController
class SpecCommentQueryController(
    private val commentService: SpecCommentService,
    private val specService: SpecService,
    private val specPermissions: SpecPermissionEvaluator,
    private val profileService: ProfileService,
) : GraphQLController<WorkOpsSpecComments> {

    private suspend fun resolveViewingProfileId(authentication: AuthenticationContext): UUID? {
        val authenticated = authentication.principal() ?: return null
        val principal = authenticated.asPrincipal()
        return principal.primaryProfileId
            ?: profileService.getByPrincipal(principal.id).firstOrNull()?.id
    }

    @Field
    suspend fun forSpec(authentication: AuthenticationContext, specId: UUID, offset: Long, limit: Long): List<SpecComment> {
        val spec = specService.getById(specId) ?: return emptyList()
        if (!specPermissions.isAllowed(authentication, spec, PermissionAction.VIEW)) return emptyList()
        if (specPermissions.isAllowed(authentication, spec, PermissionAction.MANAGE)) {
            return commentService.listManager(specId, offset, limit)
        }
        val viewingProfileId = resolveViewingProfileId(authentication)
        return if (viewingProfileId != null) {
            commentService.listForProfile(specId, viewingProfileId, offset, limit)
        } else {
            commentService.listPublic(specId, offset, limit)
        }
    }

    @Field
    suspend fun countForSpec(authentication: AuthenticationContext, specId: UUID): Long {
        val spec = specService.getById(specId) ?: return 0
        if (!specPermissions.isAllowed(authentication, spec, PermissionAction.VIEW)) return 0
        return commentService.countManager(specId)
    }

    @Field
    suspend fun comment(authentication: AuthenticationContext, specId: UUID, commentId: Long): SpecComment? {
        val spec = specService.getById(specId) ?: return null
        if (!specPermissions.isAllowed(authentication, spec, PermissionAction.VIEW)) return null
        if (specPermissions.isAllowed(authentication, spec, PermissionAction.MANAGE)) {
            return commentService.getManager(specId, commentId)
        }
        val viewingProfileId = resolveViewingProfileId(authentication)
        return if (viewingProfileId != null) {
            commentService.getForProfile(specId, commentId, viewingProfileId)
        } else {
            commentService.getPublic(specId, commentId)
        }
    }
}

object WorkOpsSpecCommentsMutation

@TypeController
class SpecCommentMutationController(
    private val commentService: SpecCommentService,
    private val specService: SpecService,
    private val specPermissions: SpecPermissionEvaluator,
    private val profileService: ProfileService,
) : GraphQLController<WorkOpsSpecCommentsMutation> {

    private suspend fun resolveProfileId(authentication: AuthenticationContext, operation: String): UUID {
        val authenticated = authentication.principal()
            ?: error("$operation requires an authenticated principal")
        val principal = authenticated.asPrincipal()
        return principal.primaryProfileId
            ?: profileService.getByPrincipal(principal.id).firstOrNull()?.id
            ?: error("$operation requires the principal to have a profile")
    }

    @Field
    suspend fun add(authentication: AuthenticationContext, specId: UUID, input: SpecCommentInput): SpecComment {
        val spec = specService.getById(specId) ?: error("addSpecComment: spec $specId not found")
        specPermissions.verifyAllowed(authentication, spec, PermissionAction.EDIT)
        val authenticated = authentication.principal()
            ?: error("addSpecComment requires an authenticated principal")
        val profileId = resolveProfileId(authentication, "addSpecComment")
        return commentService.add(specId, input, authenticated.id, profileId)
    }

    @Field
    suspend fun like(authentication: AuthenticationContext, specId: UUID, commentId: Long): Int {
        val spec = specService.getById(specId) ?: error("likeSpecComment: spec $specId not found")
        specPermissions.verifyAllowed(authentication, spec, PermissionAction.VIEW)
        val profileId = resolveProfileId(authentication, "likeSpecComment")
        commentService.like(specId, commentId, profileId)
        return commentService.getManager(specId, commentId)?.likes ?: -1
    }

    @Field
    suspend fun unlike(authentication: AuthenticationContext, specId: UUID, commentId: Long): Int {
        val spec = specService.getById(specId) ?: error("unlikeSpecComment: spec $specId not found")
        specPermissions.verifyAllowed(authentication, spec, PermissionAction.VIEW)
        val profileId = resolveProfileId(authentication, "unlikeSpecComment")
        commentService.unlike(specId, commentId, profileId)
        return commentService.getManager(specId, commentId)?.likes ?: -1
    }

    @Field
    suspend fun setStatus(authentication: AuthenticationContext, specId: UUID, commentId: Long, status: CommentStatus): Boolean {
        val spec = specService.getById(specId) ?: error("setSpecCommentStatus: spec $specId not found")
        specPermissions.verifyAllowed(authentication, spec, PermissionAction.MANAGE)
        val authenticated = authentication.principal()
            ?: error("setSpecCommentStatus requires an authenticated principal")
        val profileId = resolveProfileId(authentication, "setSpecCommentStatus")
        commentService.setStatus(specId, commentId, status, authenticated.id, profileId)
        return true
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, specId: UUID, commentId: Long): Boolean {
        val spec = specService.getById(specId) ?: error("deleteSpecComment: spec $specId not found")
        specPermissions.verifyAllowed(authentication, spec, PermissionAction.MANAGE)
        val authenticated = authentication.principal()
            ?: error("deleteSpecComment requires an authenticated principal")
        val profileId = resolveProfileId(authentication, "deleteSpecComment")
        commentService.delete(specId, commentId, authenticated.id, profileId)
        return true
    }
}
