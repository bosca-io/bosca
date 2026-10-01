package bosca.profile.profile.graphql

import bosca.content.collection.model.CollectionInput
import bosca.content.collection.service.CollectionService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.attribute.model.ProfileAttributeInput
import bosca.profile.attribute.model.ProfileAttributeTypeInput
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.profile.model.ProfileInput
import bosca.profile.persona.graphql.StudioPersonasMutation
import bosca.profile.profile.service.ProfileService
import bosca.profile.relationship.model.ProfileRelationshipRequest
import bosca.profile.relationship.service.ProfileRelationshipRequestService
import bosca.profile.relationship.service.ProfileRelationshipService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory

object ProfilesMutation

@TypeController
class ProfilesMutationController(
    private val profileService: ProfileService,
    private val profileRelationshipService: ProfileRelationshipService,
    private val profileRelationshipRequestService: ProfileRelationshipRequestService,
    private val collectionService: CollectionService,
    private val groupEvaluator: GroupEvaluator,
    private val permissionEvaluator: ProfilePermissionEvaluator,
) : GraphQLController<ProfilesMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, profile: ProfileInput, linkToPrincipal: Boolean? = null): Profile {
        val principal = authentication.principal() ?: throw SecurityException("not authenticated")
        val principalId = if (linkToPrincipal == false) null else principal.id
        return profileService.add(profile, ProfileType.GENERIC, principalId, allowProtected = groupEvaluator.hasSaGroup(authentication))
    }

    @Field
    suspend fun addChild(authentication: AuthenticationContext, profile: ProfileInput): Profile {
        val principal = authentication.principal() ?: throw SecurityException("not authenticated")
        return profileService.add(profile, ProfileType.CHILD, principal.id, allowProtected = groupEvaluator.hasSaGroup(authentication))
    }

    @Field
    suspend fun edit(
        authentication: AuthenticationContext,
        id: UUID? = null,
        profile: ProfileInput
    ): Profile? {
        val principal = authentication.principal() ?: throw SecurityException("not authenticated")
        // Only SA callers may write attributes of protected types; EDIT permission alone is not enough
        // (protected types are the platform's own control attributes, e.g. comment moderation flags).
        val allowProtected = groupEvaluator.hasSaGroup(authentication)
        val profile = if (id != null) {
            val existingProfile = profileService.getById(id)
            if (!permissionEvaluator.isAllowed(authentication, existingProfile, PermissionAction.EDIT)) {
                groupEvaluator.verifyHasSaGroup(authentication)
            }
            profileService.edit(id, profile, allowProtected)
        } else {
            val userProfile = profileService.getByPrincipal(principal.id).firstOrNull()
            if (userProfile != null) {
                profileService.edit(userProfile.id, profile, allowProtected)
            } else {
                throw NoSuchElementException("profile not found")
            }
        }
        return profile
    }

    @Field
    suspend fun deleteAttribute(
        authentication: AuthenticationContext,
        attributeId: UUID,
        id: UUID? = null
    ): Boolean {
        val principal = authentication.principal() ?: throw SecurityException("not authenticated")
        if (id != null) {
            val profile = profileService.getById(id)
            if (!permissionEvaluator.isAllowed(authentication, profile, PermissionAction.EDIT)) {
                groupEvaluator.verifyHasSaGroup(authentication)
            }
            profileService.deleteAttributeFromProfile(id, attributeId)
        } else {
            val userProfile = profileService.getByPrincipal(principal.id).firstOrNull()
            if (userProfile != null) {
                profileService.deleteAttributeFromProfile(userProfile.id, attributeId)
            } else {
                throw NoSuchElementException("profile not found")
            }
        }
        return true
    }

    @Field
    suspend fun addAttributeType(
        authentication: AuthenticationContext,
        attribute: ProfileAttributeTypeInput
    ): Boolean {
        groupEvaluator.verifyHasSaGroup(authentication)
        profileService.addAttributeType(attribute)
        return true
    }

    @Field
    suspend fun editAttributeType(
        authentication: AuthenticationContext,
        attribute: ProfileAttributeTypeInput
    ): Boolean {
        groupEvaluator.verifyHasSaGroup(authentication)
        profileService.editAttributeType(attribute)
        return true
    }

    @Field
    suspend fun deleteAttributeType(
        authentication: AuthenticationContext, attributeId: String
    ): Boolean {
        groupEvaluator.verifyHasSaGroup(authentication)
        profileService.deleteAttributeType(attributeId)
        return true
    }

    @Field
    suspend fun addAttributes(
        authentication: AuthenticationContext,
        id: UUID,
        attributes: List<ProfileAttributeInput>
    ): Boolean {
        val profile = profileService.getById(id)
        if (!permissionEvaluator.isAllowed(authentication, profile, PermissionAction.EDIT)) {
            groupEvaluator.verifyHasSaGroup(authentication)
        }
        profileService.addAttributes(id, attributes, allowProtected = groupEvaluator.hasSaGroup(authentication))
        return true
    }

    @Field
    suspend fun addCollection(authentication: AuthenticationContext, profileId: UUID): bosca.content.collection.model.Collection {
        val profile = profileService.getById(profileId)
        if (!permissionEvaluator.isAllowed(authentication, profile, PermissionAction.EDIT)) {
            groupEvaluator.verifyHasSaGroup(authentication)
        }
        val existing = profile.collectionId
        val collection = if (existing != null) {
            collectionService.getById(existing) ?: error("Profile collection $existing not found")
        } else {
            collectionService.add(CollectionInput(name = profile.name)).also {
                profileService.setCollectionId(profileId, it.id)
            }
        }
        for (permission in profileService.getPermissions(profile)) {
            collectionService.addPermission(collection.id, permission.groupId, permission.action)
        }
        return collection
    }

    @Field
    suspend fun addRelationship(
        authentication: AuthenticationContext,
        sourceProfileId: UUID,
        targetProfileId: UUID,
        type: String,
        attributes: JsonElement?
    ): Boolean {
        groupEvaluator.verifyHasSaGroup(authentication)
        profileRelationshipService.addRelationship(sourceProfileId, targetProfileId, type, attributes)
        return true
    }

    @Field
    suspend fun requestRelationship(
        authentication: AuthenticationContext,
        requesterProfileId: UUID,
        targetProfileId: UUID,
        type: String,
        attributes: JsonElement?,
    ): ProfileRelationshipRequest {
        val requester = profileService.getById(requesterProfileId)
        permissionEvaluator.verifyCanManageRelationships(authentication, requester)
        val target = profileService.getById(targetProfileId)
        permissionEvaluator.verifyAllowed(authentication, target, PermissionAction.VIEW)
        return profileRelationshipRequestService.request(
            requesterProfileId,
            targetProfileId,
            type,
            attributes,
        )
    }

    @Field
    suspend fun approveRelationshipRequest(
        authentication: AuthenticationContext,
        requestId: UUID,
    ): ProfileRelationshipRequest {
        val request = getRelationshipRequest(requestId)
        val target = profileService.getById(request.targetProfileId)
        permissionEvaluator.verifyCanManageRelationships(authentication, target)
        return profileRelationshipRequestService.approve(requestId)
    }

    @Field
    suspend fun declineRelationshipRequest(
        authentication: AuthenticationContext,
        requestId: UUID,
    ): ProfileRelationshipRequest {
        val request = getRelationshipRequest(requestId)
        val target = profileService.getById(request.targetProfileId)
        permissionEvaluator.verifyCanManageRelationships(authentication, target)
        return profileRelationshipRequestService.decline(requestId)
    }

    @Field
    suspend fun cancelRelationshipRequest(
        authentication: AuthenticationContext,
        requestId: UUID,
    ): ProfileRelationshipRequest {
        val request = getRelationshipRequest(requestId)
        val requester = profileService.getById(request.requesterProfileId)
        permissionEvaluator.verifyCanManageRelationships(authentication, requester)
        return profileRelationshipRequestService.cancel(requestId)
    }

    @Field
    suspend fun removeRelationship(
        authentication: AuthenticationContext,
        sourceProfileId: UUID,
        targetProfileId: UUID,
        type: String
    ): Boolean {
        val source = profileService.getById(sourceProfileId)
        val target = profileService.getById(targetProfileId)
        permissionEvaluator.verifyCanManageEitherRelationshipParticipant(authentication, source, target)
        profileRelationshipService.removeRelationship(sourceProfileId, targetProfileId, type)
        return true
    }

    private suspend fun getRelationshipRequest(requestId: UUID): ProfileRelationshipRequest =
        profileRelationshipRequestService.getById(requestId)
            ?: throw NoSuchElementException("relationship request not found")

    @Field
    suspend fun setPrincipal(authentication: AuthenticationContext, id: UUID, principalId: UUID): Profile {
        groupEvaluator.verifyHasSaGroup(authentication)
        val caller = authentication.principal()?.id
        log.info("setPrincipal: profile={} principalId={} caller={}", id, principalId, caller)
        return profileService.setPrincipal(id, principalId)
    }

    @Field
    suspend fun clearPrincipal(authentication: AuthenticationContext, id: UUID): Profile {
        groupEvaluator.verifyHasSaGroup(authentication)
        val caller = authentication.principal()?.id
        log.info("clearPrincipal: profile={} caller={}", id, caller)
        return profileService.clearPrincipal(id)
    }

    /** Marks a profile deleted — the reversible staging step before [delete]. */
    @Field
    suspend fun markDeleted(authentication: AuthenticationContext, id: UUID): Profile {
        groupEvaluator.verifyHasSaGroup(authentication)
        log.info("markDeleted: profile={} caller={}", id, authentication.principal()?.id)
        return profileService.markDeleted(id)
    }

    /** Reverses [markDeleted], restoring the profile. */
    @Field
    suspend fun restore(authentication: AuthenticationContext, id: UUID): Profile {
        groupEvaluator.verifyHasSaGroup(authentication)
        log.info("restore: profile={} caller={}", id, authentication.principal()?.id)
        return profileService.restore(id)
    }

    /**
     * Permanently deletes a profile. The profile MUST already be soft-deleted (via [markDeleted]);
     * this two-stage requirement guards against accidental one-click destruction.
     */
    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        groupEvaluator.verifyHasSaGroup(authentication)
        val profile = profileService.getById(id)
        if (profile.deletedAt == null) {
            throw SecurityException("profile must be marked deleted before it can be permanently deleted")
        }
        log.info("delete: profile={} caller={}", id, authentication.principal()?.id)
        profileService.delete(id)
        return true
    }

    @Field
    fun profile() = ProfileMutation

    @Field
    fun studioPersonas() = StudioPersonasMutation

    companion object {

        private val log = LoggerFactory.getLogger(ProfilesMutationController::class.java)
    }
}
