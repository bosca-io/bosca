package bosca.comments.graphql

import bosca.comments.model.Comment
import bosca.comments.model.CommentStatus
import bosca.comments.service.CommentService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.model.Profile
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@TypeController
class CommentController(
    private val commentService: CommentService,
    private val profileService: ProfileService,
    private val profilePermissionEvaluator: ProfilePermissionEvaluator,
    private val metadataService: MetadataService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
) : GraphQLController<Comment> {

    // Bosca's GraphQL wiring requires an explicit @Field for every SDL field,
    // including plain scalar properties — there is no default property fetcher.
    @Field
    fun id(comment: Comment): Long = comment.id

    @Field
    fun metadataId(comment: Comment): UUID? = comment.metadataId

    @Field
    fun version(comment: Comment): Int? = comment.version

    @Field
    fun created(comment: Comment): OffsetDateTime = comment.created

    @Field
    fun modified(comment: Comment): OffsetDateTime = comment.modified

    @Field
    fun status(comment: Comment): CommentStatus = comment.status

    @Field
    fun content(comment: Comment): String = comment.content

    @Field
    fun attributes(comment: Comment): JsonElement? = comment.attributes

    @Field
    fun systemAttributes(comment: Comment): JsonElement? = comment.systemAttributes

    @Field
    fun likes(comment: Comment): Int = comment.likes

    @Field
    fun pinned(comment: Comment): Boolean = comment.pinned

    @Field
    fun visibility(comment: Comment): ProfileVisibility = comment.visibility

    @Field
    suspend fun likedByMe(authentication: AuthenticationContext?, comment: Comment): Boolean {
        val profileId = resolveProfileId(authentication) ?: return false
        return commentService.hasLiked(comment.id, profileId)
    }

    @Field
    suspend fun profile(authentication: AuthenticationContext?, comment: Comment): Profile? {
        val profileId = comment.profileId ?: return null
        val profile = profileService.getById(profileId)
        // TODO: Think through this
//        if (!profilePermissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW)) return null
        return profile
    }

    /**
     * Threaded replies to this comment, as a [Comments] page. Replies inherit
     * the caller's visibility: a manager (moderator) sees every status —
     * including blocked and pending — so they can act on flagged replies;
     * everyone else sees only public-approved replies and their own.
     */
    @Field
    suspend fun replies(
        authentication: AuthenticationContext?,
        comment: Comment,
        limit: Int,
        offset: Int,
    ): Comments {
        val metadataId = comment.metadataId ?: return Comments(emptyList(), 0)
        val version = comment.version ?: return Comments(emptyList(), 0)
        val manager = isManager(authentication, metadataId, version)
        val profileId = resolveProfileId(authentication)
        val replies = commentService.getMetadataCommentsByParentId(
            profileId = profileId,
            metadataId = metadataId,
            version = version,
            parentId = comment.id,
            manager = manager,
            offset = offset.toLong(),
            limit = limit.toLong(),
        )
        val count = commentService.getMetadataCommentsCountByParentId(
            profileId = profileId,
            metadataId = metadataId,
            version = version,
            parentId = comment.id,
            manager = manager,
        )
        return Comments(replies, count.toInt())
    }

    private suspend fun isManager(authentication: AuthenticationContext?, metadataId: UUID, version: Int): Boolean {
        val metadata = metadataService.getById(metadataId, version) ?: return false
        return metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.MANAGE)
    }

    private suspend fun resolveProfileId(authentication: AuthenticationContext?): UUID? {
        val principal = authentication?.principal()?.asPrincipal() ?: return null
        return profileService.getPrimaryProfile(principal)?.id
    }
}
