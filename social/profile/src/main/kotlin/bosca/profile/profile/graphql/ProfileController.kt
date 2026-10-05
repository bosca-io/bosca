package bosca.profile.profile.graphql

import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.graphql.Batch
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.bookmark.graphql.ProfileBookmarks
import bosca.profile.bookmark.service.ProfileBookmarkService
import bosca.profile.guide.graphql.ProfileGuides
import bosca.profile.mark.graphql.ProfileMarks
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.organization.model.Organization
import bosca.profile.organization.service.OrganizationService
import bosca.profile.profile.service.ProfileService
import bosca.profile.relationship.model.ProfileRelationship
import bosca.profile.relationship.model.ProfileRelationshipRequest
import bosca.profile.relationship.service.ProfileRelationshipRequestService
import bosca.profile.relationship.service.ProfileRelationshipService
import bosca.profile.security.OrganizationPermissionEvaluator
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.serialization.OffsetDateTime
import bosca.slug.service.SlugService
import bosca.security.service.AuthenticationContext

@TypeController
class ProfileController(
    private val profileService: ProfileService,
    private val securityService: SecurityService,
    private val collectionPermissionEvaluator: CollectionPermissionEvaluator,
    private val groupEvaluator: GroupEvaluator,
    private val slugService: SlugService,
    private val collectionService: CollectionService,
    private val organizationService: OrganizationService,
    private val organizationPermissionEvaluator: OrganizationPermissionEvaluator,
    private val profileRelationshipService: ProfileRelationshipService,
    private val profileRelationshipRequestService: ProfileRelationshipRequestService,
    private val profilePermissionEvaluator: ProfilePermissionEvaluator,
) : GraphQLController<Profile> {

    @Field
    fun id(authentication: AuthenticationContext?, profile: Profile): UUID {
        if (profile.type == ProfileType.ORGANIZATION) return profile.id
        if (authentication?.principal()?.id != profile.principal && !groupEvaluator.hasSaGroup(authentication)) {
            return UUID.NIL
        }
        return profile.id
    }

    @Field
    fun created(authentication: AuthenticationContext, profile: Profile): OffsetDateTime {
        if (authentication.principal()?.id != profile.principal && !groupEvaluator.hasSaGroup(authentication)) {
            return OffsetDateTime.MIN
        }
        return profile.created
    }

    @Field
    fun modified(authentication: AuthenticationContext, profile: Profile): OffsetDateTime {
        if (authentication.principal()?.id != profile.principal && !groupEvaluator.hasSaGroup(authentication)) {
            return OffsetDateTime.MIN
        }
        return profile.modified
    }

    @Field
    fun deletedAt(authentication: AuthenticationContext?, profile: Profile): OffsetDateTime? {
        if (authentication?.principal()?.id != profile.principal && !groupEvaluator.hasSaGroup(authentication)) {
            return null
        }
        return profile.deletedAt
    }

    @Field
    fun name(profile: Profile) = profile.name

    @Field
    fun type(profile: Profile) = profile.type

    @Field
    fun visibility(profile: Profile) = profile.visibility

    @Field
    fun searchable(profile: Profile) = profile.searchable

    @Field
    suspend fun isPrimary(profile: Profile): Boolean {
        val principalId = profile.principal ?: return false
        val principal = securityService.getPrincipalById(principalId) ?: return false
        return principal.primaryProfileId == profile.id
    }

    @Field
    suspend fun slug(batch: Batch<UUID, String>) {
        slugService.addProfileSlugsToBatch(batch)
    }

    @Field
    suspend fun principal(authentication: AuthenticationContext?, profile: Profile): Principal? {
        if (authentication == null) return null
        if (authentication.principal()?.id != profile.principal && !groupEvaluator.hasSaGroup(authentication)) {
            return null
        }
        return securityService.getPrincipalById(profile.principal ?: return null)
    }

    @Field
    suspend fun organizations(authentication: AuthenticationContext?, profile: Profile): List<Organization> {
        if (authentication == null) return emptyList()
        if (authentication.principal()?.id != profile.principal && !groupEvaluator.hasSaGroup(authentication)) {
            return emptyList()
        }
        if (profile.type == ProfileType.ORGANIZATION) {
            val organization = organizationService.getOrganizationByProfile(profile.id) ?: return emptyList()
            organizationPermissionEvaluator.verifyAllowed(authentication, organization, PermissionAction.VIEW)
            return listOf(organization)
        }
        val memberships = organizationService.getMemberOrganizations(profile.principal ?: return emptyList())
        val organizations = organizationService.getOrganizations(memberships.map { it.organizationId })
        val allowed = organizations.filter {
            organizationPermissionEvaluator.isAllowed(authentication, it, PermissionAction.VIEW)
        }
        return allowed
    }

    @Field
    suspend fun relationships(
        authentication: AuthenticationContext,
        profile: Profile,
        type: String? = null,
        limit: Int? = null,
        offset: Long? = null,
    ): List<ProfileRelationship> {
        profilePermissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.VIEW)
        return profileRelationshipService.getRelationships(
            profileId = profile.id,
            type = type,
            offset = offset ?: 0,
            limit = limit ?: Int.MAX_VALUE,
        )
    }

    @Field
    suspend fun incomingRelationshipRequests(
        authentication: AuthenticationContext,
        profile: Profile,
        type: String? = null,
        limit: Int? = null,
        offset: Long? = null,
    ): List<ProfileRelationshipRequest> {
        profilePermissionEvaluator.verifyCanManageRelationships(authentication, profile)
        return profileRelationshipRequestService.getIncoming(
            profileId = profile.id,
            type = type,
            offset = offset ?: 0,
            limit = limit ?: Int.MAX_VALUE,
        )
    }

    @Field
    suspend fun outgoingRelationshipRequests(
        authentication: AuthenticationContext,
        profile: Profile,
        type: String? = null,
        limit: Int? = null,
        offset: Long? = null,
    ): List<ProfileRelationshipRequest> {
        profilePermissionEvaluator.verifyCanManageRelationships(authentication, profile)
        return profileRelationshipRequestService.getOutgoing(
            profileId = profile.id,
            type = type,
            offset = offset ?: 0,
            limit = limit ?: Int.MAX_VALUE,
        )
    }

    @Field
    suspend fun attributes(
        authentication: AuthenticationContext?,
        profile: Profile
    ): List<ProfileAttribute> {
        val attributes = profileService.getAttributes(profile.id)
        // Super-admins and internal SA-grouped services administer profiles and see every attribute,
        // including the SYSTEM namespace (moderation flags, internal state).
        if (groupEvaluator.hasSaGroup(authentication)) return attributes
        val viewerPrincipal = authentication?.principal()
        val viewerPrincipalId = viewerPrincipal?.id
        // The owner sees all of their own attributes except SYSTEM — that namespace is reserved for
        // administrators and platform services and is never surfaced to the user it describes.
        if (viewerPrincipalId != null && viewerPrincipalId == profile.principal) {
            return attributes.filter { it.visibility != ProfileVisibility.SYSTEM }
        }
        val viewerProfile = viewerPrincipal?.let { profileService.getPrimaryProfile(it.asPrincipal()) }
        val isFriend = viewerProfile != null && profileRelationshipService.getRelationship(
            viewerProfile.id,
            profile.id,
            FRIEND_RELATIONSHIP_TYPE,
        ) != null
        if (isFriend) {
            return attributes.filter {
                it.visibility == ProfileVisibility.FRIENDS || it.visibility == ProfileVisibility.PUBLIC
            }
        }
        // USER is owner-only, SYSTEM is admin/service-only, and FRIENDS_OF_FRIENDS requires a
        // separate two-hop graph check. Callers without a direct friend relationship see PUBLIC only.
        return attributes.filter { it.visibility == ProfileVisibility.PUBLIC }
    }

    @Field
    suspend fun collection(
        authentication: AuthenticationContext?,
        profile: Profile
    ): Collection? {
        if (authentication?.principal()?.id != profile.principal && !groupEvaluator.hasSaGroup(authentication)) {
            return null
        }
        val collectionId = profile.collectionId ?: return null
        val collection = collectionService.getById(collectionId) ?: return null
        if (collectionPermissionEvaluator.isAllowed(authentication, collection, PermissionAction.VIEW)) {
            return collection
        }
        return null
    }

    @Field
    fun guides(profile: Profile) = ProfileGuides(profile)

    @Field
    fun marks(profile: Profile) = ProfileMarks(profile)

    @Field
    fun bookmarks(profile: Profile) = ProfileBookmarks(profile)

    @Field
    suspend fun lastLogin(authentication: AuthenticationContext?, profile: Profile): OffsetDateTime? {
        val principalId = profile.principal ?: return null
        val authenticatedPrincipal = authentication?.principal()
        if (authenticatedPrincipal?.id == principalId || groupEvaluator.hasSaGroup(authentication)) {
            return securityService.getPrincipalLastLogin(principalId)
        }
        return null
    }

    @Field
    fun community(authentication: AuthenticationContext?, profile: Profile): ProfileCommunity? {
        if (authentication == null) return null
        if (authentication.principal()?.id != profile.principal && !groupEvaluator.hasSaGroup(authentication)) {
            return null
        }
        return ProfileCommunity(profile)
    }

    @Field
    suspend fun chat(authentication: AuthenticationContext?, profile: Profile): ProfileChat? {
        val principal = authentication?.principal()?.asPrincipal() ?: return null
        val primaryProfile = profileService.getPrimaryProfile(principal) ?: return null
        return if (primaryProfile.id == profile.id) ProfileChat(profile) else null
    }

    @Field
    fun thirdparty(profile: Profile): ThirdPartyExtension = ThirdPartyExtension(profile.id)

    private companion object {
        const val FRIEND_RELATIONSHIP_TYPE = "friend"
    }
}
