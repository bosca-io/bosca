package bosca.profile.profile.service

import bosca.cache.ServiceCache
import bosca.cache.serializers.UUIDKeySerializer
import bosca.db.transaction
import bosca.di.ObjectProvider
import bosca.di.provide
import bosca.graphql.Batch
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.attribute.model.ProfileAttributeInput
import bosca.profile.attribute.verification.AttributeVerificationService
import bosca.profile.attribute.model.ProfileAttributeTypeInput
import bosca.profile.attribute.model.ProfileAttributesFilterInput
import bosca.profile.attribute.service.ProfileAttributeService
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.organization.service.OrganizationService
import bosca.profile.profile.events.ProfileCreatedEvent
import bosca.profile.profile.events.ProfileDeletedEvent
import bosca.profile.profile.events.ProfileUpdatedEvent
import bosca.profile.profile.events.ProfileUnlinkedEvent
import bosca.profile.profile.events.dispatch
import bosca.profile.profile.model.ProfileInput
import bosca.profile.profile.model.ProfilePermission
import bosca.profile.profile.repository.ProfileRepository
import bosca.security.model.EntityPermission
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.slug.model.Slug
import bosca.slug.service.SlugService
import kotlinx.coroutines.CancellationException

@ServiceImplementation
class ProfileServiceImpl(
    private val repository: ProfileRepository,
    private val securityService: ObjectProvider<SecurityService>,
    private val organizationService: ObjectProvider<OrganizationService>,
    private val attributeService: ProfileAttributeService,
    private val slugService: SlugService,
) : ProfileService {

    private val profileByIdCache = ServiceCache(
        "profile:id",
        UUIDKeySerializer,
        { keys, batch ->
            val profiles = repository.getAllByIds(keys).associateBy { it.id }
            batch.setData(keys, profiles)
        }
    ) {
        repository.getById(it)
    }

    private val profileByPrincipalIdCache = ServiceCache(
        "profile:principal",
        UUIDKeySerializer,
    ) {
        repository.getByPrincipal(it)
    }

    override suspend fun getAll(offset: Long, limit: Int) = repository.getAll(offset, limit)

    override suspend fun getAllByType(offset: Long, limit: Int, type: ProfileType) = repository.getAllByType(offset, limit, type)

    override suspend fun getAllByIds(ids: List<UUID>): List<Profile> {
        return profileByIdCache.getAll(ids).filterNotNull()
    }

    override suspend fun getById(id: UUID) = profileByIdCache.get(id) ?: error("Profile not found: $id")

    override suspend fun getBySlug(slug: String): Profile? {
        val profileId = slugService.get(slug)?.profileId ?: return null
        return profileByIdCache.get(profileId)
    }

    override suspend fun getByNames(names: List<String>): List<Profile> {
        val normalizedNames = names.map { it.lowercase() }.distinct()
        return if (normalizedNames.isEmpty()) emptyList() else repository.getByNames(normalizedNames)
    }

    override suspend fun getByPrincipal(principalId: UUID) = profileByPrincipalIdCache.get(principalId) ?: emptyList()

    override suspend fun getPrimaryProfile(principal: Principal): Profile? {
        principal.primaryProfileId?.let { profileId ->
            return getById(profileId).takeIf { it.principal == principal.id && !it.isDeleted }
        }
        return getByPrincipal(principal.id).firstOrNull { !it.isDeleted }
    }

    override suspend fun getByGroupName(groupName: String, offset: Long, limit: Int): List<Profile> {
        return repository.getByGroupName(groupName, offset, limit)
    }

    override suspend fun getProfilesByEmail(email: String): List<Profile> {
        val ids = attributeService.getProfileIdsByEmail(email)
        return ids.map { getById(it) }
    }

    override suspend fun getAttributeTypes() = attributeService.getAllAttributeTypes()

    override suspend fun getAttributes(profileId: UUID) = attributeService.getAttributesByProfile(profileId)

    override suspend fun addAttributesToBatch(batch: Batch<UUID, List<ProfileAttribute>>) = attributeService.addAttributesToBatch(batch)

    override suspend fun getPermissions(entity: Profile): List<EntityPermission> {
        if (entity.type == ProfileType.ORGANIZATION) {
            val organizationService = organizationService.get()
            val organization = organizationService.getOrganizationByProfile(entity.id) ?: return emptyList()
            return organizationService.getPermissions(organization)
        }
        val securityService = securityService.get()
        val principal = securityService.getPrincipalById(entity.principal ?: return emptyList()) ?: return emptyList()
        if (principal.id != entity.principal) return emptyList()
        val groups = securityService.getPrincipalGroups(principal.id)
        var group = groups.firstOrNull { it.type == GroupType.PRINCIPAL }
        if (group == null) {
            // backfill missing group
            var newGroup = securityService.getGroupByName("${principal.id}.user", GroupType.PRINCIPAL)
            if (newGroup == null) {
                newGroup = Group(name = "${principal.id}.user", description = "${principal.id} User", type = GroupType.PRINCIPAL)
                group = securityService.addGroup(newGroup)
                securityService.addPrincipalGroup(principal.id, group.id)
            } else {
                securityService.addPrincipalGroup(principal.id, newGroup.id)
                group = newGroup
            }
        }
        return listOf(
            ProfilePermission(
                entityId = entity.id,
                groupId = group.id,
                action = PermissionAction.VIEW
            ),
            ProfilePermission(
                entityId = entity.id,
                groupId = group.id,
                action = PermissionAction.LIST
            ),
            ProfilePermission(
                entityId = entity.id,
                groupId = group.id,
                action = PermissionAction.EDIT
            )
        )
    }

    override suspend fun addPermissionsToBatch(batch: Batch<UUID, List<EntityPermission>>) {
        batch.keys.forEach { id ->
            val profile = getById(id)
            batch.setData(id, getPermissions(profile))
        }
    }

    override suspend fun getAttributesByProfileWithFilter(
        profileId: UUID,
        filter: ProfileAttributesFilterInput
    ): List<ProfileAttribute> {
        return attributeService.getAttributesByProfileWithFilter(profileId, filter)
    }

    override suspend fun add(input: ProfileInput, type: ProfileType, principalId: UUID?, allowProtected: Boolean): Profile {
        val profile = transaction {
            val profile = Profile(
                name = input.name,
                visibility = input.visibility,
                searchable = input.searchable ?: true,
                principal = principalId,
                type = type
            )
            val savedProfile = repository.add(profile)
            generateAndSaveProfileSlug(savedProfile)
            if (input.attributes.isNotEmpty()) {
                // Through the wrapped addAttributes so the verification framework runs (a no-op on create —
                // nothing is verified yet — but keeps the single guarded entry point).
                addAttributes(savedProfile.id, input.attributes, allowProtected = allowProtected)
            }
            savedProfile
        }
        ProfileCreatedEvent(profile).dispatch()
        return profile
    }

    override suspend fun edit(id: UUID, input: ProfileInput, allowProtected: Boolean): Profile {
        val profile = transaction {
            val existing = repository.getById(id) ?: throw NoSuchElementException("Profile not found: $id")
            val updated = existing.copy(
                name = input.name,
                visibility = input.visibility,
                searchable = input.searchable ?: existing.searchable,
            )
            val savedProfile = repository.update(updated)
            val currentSlug = slugService.getProfileSlug(id)
            val newSlug = input.slug
            if (newSlug != null && newSlug != currentSlug) {
                saveProfileSlug(newSlug, savedProfile.id)
            } else if (newSlug == null && existing.name != input.name) {
                generateAndSaveProfileSlug(savedProfile)
            }
            if (input.attributes.isNotEmpty()) {
                // Through the wrapped addAttributes so a verified-email change here runs the same guard
                // (assertEmailChangeAllowed) and re-verification as the addAttributes mutation — not bypassed.
                addAttributes(savedProfile.id, input.attributes, allowProtected = allowProtected)
            }
            profileByIdCache.remove(id)
            existing.principal?.let { profileByPrincipalIdCache.remove(it) }
            savedProfile
        }
        ProfileUpdatedEvent(profile).dispatch()
        return profile
    }

    override suspend fun setPrincipal(id: UUID, principalId: UUID): Profile {
        val profile = transaction {
            val existing = repository.getById(id) ?: throw NoSuchElementException("Profile not found: $id")
            securityService.get().getPrincipalById(principalId)
                ?: throw NoSuchElementException("Principal not found: $principalId")
            if (existing.principal == principalId) return@transaction existing
            val updated = repository.setPrincipal(id, principalId)
            profileByIdCache.remove(id)
            existing.principal?.let { profileByPrincipalIdCache.remove(it) }
            profileByPrincipalIdCache.remove(principalId)
            updated
        }
        ProfileUpdatedEvent(profile).dispatch()
        return profile
    }

    override suspend fun clearPrincipal(id: UUID): Profile {
        val profile = transaction {
            val existing = repository.getById(id) ?: throw NoSuchElementException("Profile not found: $id")
            val previousPrincipalId = existing.principal ?: return@transaction existing
            val updated = repository.clearPrincipal(id)
            profileByIdCache.remove(id)
            profileByPrincipalIdCache.remove(previousPrincipalId)
            val security = securityService.get()
            val principal = security.getPrincipalById(previousPrincipalId)
            if (principal?.primaryProfileId == id) {
                security.clearPrimaryProfile(previousPrincipalId)
            }
            ProfileUnlinkedEvent(id, previousPrincipalId).dispatch()
            updated
        }
        ProfileUpdatedEvent(profile).dispatch()
        return profile
    }

    override suspend fun setCollectionId(id: UUID, collectionId: UUID): Profile {
        val profile = repository.setCollectionId(id, collectionId)
        profileByIdCache.remove(id)
        profile.principal?.let { profileByPrincipalIdCache.remove(it) }
        ProfileUpdatedEvent(profile).dispatch()
        return profile
    }

    override suspend fun markDeleted(id: UUID): Profile {
        val profile = transaction {
            val existing = repository.getById(id) ?: throw NoSuchElementException("Profile not found: $id")
            if (existing.isDeleted) return@transaction existing
            val updated = repository.markDeleted(id)
            profileByIdCache.remove(id)
            existing.principal?.let { profileByPrincipalIdCache.remove(it) }
            updated
        }
        // A soft-deleted profile reports isSearchable = false regardless of its saved preference, so
        // the reindex this update triggers drops it from search until it is restored.
        ProfileUpdatedEvent(profile).dispatch()
        return profile
    }

    override suspend fun restore(id: UUID): Profile {
        val profile = transaction {
            val existing = repository.getById(id) ?: throw NoSuchElementException("Profile not found: $id")
            if (!existing.isDeleted) return@transaction existing
            val updated = repository.restore(id)
            profileByIdCache.remove(id)
            updated.principal?.let { profileByPrincipalIdCache.remove(it) }
            updated
        }
        ProfileUpdatedEvent(profile).dispatch()
        return profile
    }

    override suspend fun delete(id: UUID) = transaction {
        val profile = getById(id)
        repository.deleteById(id)
        profileByIdCache.remove(id)
        profile.principal?.let { profileByPrincipalIdCache.remove(it) }
        ProfileDeletedEvent(profile).dispatch()
    }

    override suspend fun addAttributeType(input: ProfileAttributeTypeInput) = attributeService.addAttributeType(input)

    override suspend fun editAttributeType(input: ProfileAttributeTypeInput) = attributeService.editAttributeType(input)

    override suspend fun deleteAttributeType(id: String) = attributeService.deleteAttributeType(id)

    override suspend fun addAttributes(profileId: UUID, attributes: List<ProfileAttributeInput>, origin: String?, allowProtected: Boolean): List<ProfileAttribute> = transaction {
        // Snapshot the profile's attributes before the edit, then hand before/after to the generic
        // verification framework: it detects any registered verifiable attribute whose previously-VERIFIED
        // value changed (email today, phone later) and re-verifies it. In ONE transaction with the edit, so a
        // rejected change (rate limit / take-over guard) rolls the edit back; the challenge is enqueued
        // (deferred to commit) so it never fires on a rolled-back edit.
        val before = attributeService.getAttributesByProfile(profileId)
        val result = attributeService.addAttributes(profileId, attributes, allowProtected)
        repository.setModified(profileId)
        // Hand the framework the FULL pre- and post-edit snapshots (not just the edited subset) so it can see
        // what changed AND what was removed. addAttributes invalidated the per-profile cache, so this re-read
        // reflects the in-transaction state. Return the edited rows (the mutation's contract) unchanged.
        val after = attributeService.getAttributesByProfile(profileId)
        provide<AttributeVerificationService>().onAttributesChanged(profileId, before, after, origin)
        result
    }

    override suspend fun markVerified(typeId: String, profileIds: List<UUID>, key: String, value: String, source: String): List<ProfileAttribute> {
        val updated = attributeService.markVerified(typeId, profileIds, key, value, source)
        updated.map { it.profile }.distinct().forEach { repository.setModified(it) }
        return updated
    }

    override suspend fun setVerificationToken(typeId: String, profileId: UUID, key: String, value: String, token: String, origin: String?): List<ProfileAttribute> =
        attributeService.setVerificationToken(typeId, profileId, key, value, token, origin)

    override suspend fun getByVerificationToken(token: String): ProfileAttribute? =
        attributeService.getByVerificationToken(token)

    override suspend fun verifyByToken(token: String, source: String): List<ProfileAttribute> {
        val updated = attributeService.verifyByToken(token, source)
        updated.map { it.profile }.distinct().forEach { repository.setModified(it) }
        return updated
    }

    override suspend fun deleteAttribute(attributeId: UUID) {
        val profile = attributeService.deleteAttribute(attributeId)
        repository.setModified(profile)
    }

    override suspend fun deleteAttributeFromProfile(profileId: UUID, attributeId: UUID) {
        // For now, just delete the attribute regardless of profile
        // This can be enhanced when attribute-profile relationships are needed
        val profile = attributeService.deleteAttribute(profileId, attributeId)
        repository.setModified(profile)
    }

    private suspend fun generateAndSaveProfileSlug(profile: Profile) {
        val profileId = profile.id

        // Generate slug from profile name
        var slugValue = generateSlugFromName(profile.name)
        var tries = 0
        var slots = 8
        while (tries++ < 5) {
            try {
                transaction {
                    saveProfileSlug(slugValue, profileId)
                }
                return
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                slugValue = makeSlugUnique(slugValue, profileId, slots)
                slots += 8
            }
        }
    }

    private fun generateSlugFromName(name: String): String {
        return name.lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .trim('-')
    }

    private fun makeSlugUnique(baseSlug: String, profileId: UUID, slots: Int): String {
        return "${baseSlug}-${profileId.toString().take(slots)}"
    }

    private suspend fun saveProfileSlug(slugValue: String, profileId: UUID) {
        slugService.deleteProfileSlug(profileId)
        val slug = Slug(
            slug = slugValue,
            profileId = profileId
        )
        slugService.add(slug)
    }
}
