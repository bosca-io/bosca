package bosca.profile.relationship.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.profile.relationship.model.ProfileRelationshipRequest
import bosca.profile.relationship.model.ProfileRelationshipRequestStatus
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

/** Field resolver for the ProfileRelationshipRequest GraphQL type. */
@TypeController(type = "ProfileRelationshipRequest")
class ProfileRelationshipRequestController(
    private val profileService: ProfileService,
    private val permissionEvaluator: ProfilePermissionEvaluator,
) : GraphQLController<ProfileRelationshipRequest> {

    @Field
    fun id(request: ProfileRelationshipRequest): UUID = request.id

    @Field
    suspend fun requester(
        authentication: AuthenticationContext,
        request: ProfileRelationshipRequest,
    ): Profile = participants(authentication, request).first

    @Field
    suspend fun target(
        authentication: AuthenticationContext,
        request: ProfileRelationshipRequest,
    ): Profile = participants(authentication, request).second

    @Field
    fun type(request: ProfileRelationshipRequest): String = request.type

    @Field
    fun attributes(request: ProfileRelationshipRequest): JsonElement? = request.attributes

    @Field
    fun status(request: ProfileRelationshipRequest): ProfileRelationshipRequestStatus = request.status

    @Field
    fun created(request: ProfileRelationshipRequest): OffsetDateTime = request.created

    @Field
    fun modified(request: ProfileRelationshipRequest): OffsetDateTime = request.modified

    private suspend fun participants(
        authentication: AuthenticationContext,
        request: ProfileRelationshipRequest,
    ): Pair<Profile, Profile> {
        val requester = profileService.getById(request.requesterProfileId)
        val target = profileService.getById(request.targetProfileId)
        permissionEvaluator.verifyCanManageEitherRelationshipParticipant(authentication, requester, target)
        return requester to target
    }
}
