package bosca.profile.attribute.service

import bosca.cache.ServiceCache
import bosca.cache.serializers.StringKeySerializer
import bosca.cache.serializers.UUIDKeySerializer
import bosca.cache.serializers.UnitKeySerializer
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.graphql.Batch
import bosca.profile.attribute.events.ProfileAttributeDeleted
import bosca.profile.attribute.events.ProfileAttributeTypeCreated
import bosca.profile.attribute.events.ProfileAttributeTypeDeleted
import bosca.profile.attribute.events.ProfileAttributeTypeUpdated
import bosca.profile.attribute.events.ProfileAttributesAdded
import bosca.profile.attribute.events.ProfileAttributesUpdated
import bosca.profile.attribute.events.ProfileAttributesVerified
import bosca.profile.attribute.events.dispatch
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.attribute.model.ProfileAttributeInput
import bosca.profile.attribute.model.ProfileAttributeType
import bosca.profile.attribute.model.ProfileAttributeTypeInput
import bosca.profile.attribute.model.ProfileAttributesFilterInput
import bosca.profile.attribute.model.getAttributeString
import bosca.profile.attribute.repository.ProfileAttributeRepository
import bosca.profile.attribute.repository.ProfileAttributeTypeRepository
import kotlinx.serialization.json.JsonElement
import bosca.profile.attribute.verification.VerifiableAttributeType
import bosca.security.service.SecurityException
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class ProfileAttributeServiceImpl(
    private val profileAttributeRepository: ProfileAttributeRepository,
    private val attributeTypeRepository: ProfileAttributeTypeRepository,
) : ProfileAttributeService {

    private val attributeTypesAll = ServiceCache(cacheName = "profile_attribute_types:all", UnitKeySerializer) {
        attributeTypeRepository.getAll()
    }

    private val attributeTypeById = ServiceCache(cacheName = "profile_attribute_types:id", StringKeySerializer) {
        attributeTypeRepository.getById(it) ?: error("missing profile attribute type: $it")
    }

    private val profileAttributesById = ServiceCache(cacheName = "profile_attribute:id", UUIDKeySerializer, { keys, batch ->
        val attributes = profileAttributeRepository.getByProfiles(keys).groupBy { it.profile }
        keys.forEach {
            batch.setData(it, attributes[it] ?: emptyList())
        }
    }) {
        profileAttributeRepository.getByProfile(it)
    }

    override suspend fun getAllAttributeTypes() = attributeTypesAll.get(Unit) ?: emptyList()

    override suspend fun getAttributeTypeById(id: String) = attributeTypeById.get(id)

    override suspend fun addAttributeType(input: ProfileAttributeTypeInput): ProfileAttributeType {
        val attributeType = ProfileAttributeType(
            id = input.id,
            name = input.name,
            description = input.description,
            visibility = input.visibility,
            protected = input.protected,
            formSchemaId = input.formSchemaId
        )
        val added = attributeTypeRepository.add(attributeType)
        attributeTypesAll.clear()
        ProfileAttributeTypeCreated(added.id).dispatch()
        return added
    }

    override suspend fun editAttributeType(input: ProfileAttributeTypeInput): ProfileAttributeType {
        val existing = attributeTypeRepository.getById(input.id) ?: error("missing profile attribute type: ${input.id}")
        val updated = existing.copy(
            name = input.name,
            description = input.description,
            visibility = input.visibility,
            protected = input.protected,
            formSchemaId = input.formSchemaId
        )
        val update = attributeTypeRepository.update(updated)
        attributeTypesAll.clear()
        attributeTypeById.remove(input.id)
        ProfileAttributeTypeUpdated(update.id).dispatch()
        return update
    }

    override suspend fun deleteAttributeType(id: String) {
        attributeTypeRepository.deleteById(id)
        attributeTypesAll.clear()
        attributeTypeById.remove(id)
        ProfileAttributeTypeDeleted(id).dispatch()
    }

    override suspend fun getById(attributeId: UUID): ProfileAttribute? {
        return profileAttributeRepository.getById(attributeId)
    }

    override suspend fun getByTypeId(typeId: String): List<ProfileAttribute> {
        return profileAttributeRepository.getByTypeId(typeId)
    }

    override suspend fun setSignals(attributeId: UUID, signals: JsonElement?) {
        profileAttributeRepository.setSignals(attributeId, signals)
    }

    override suspend fun getAttributesByProfile(profileId: UUID): List<ProfileAttribute> {
        return profileAttributesById.get(profileId) ?: emptyList()
    }

    override suspend fun getProfileIdsByEmail(email: String): List<UUID> {
        // Email-identity lookup, expressed via the generic verified-value primitive.
        return profileAttributeRepository.getVerifiedByValue("bosca.profiles.email", "email", email.trim()).map { it.profile }
    }

    override suspend fun markVerified(typeId: String, profileIds: List<UUID>, key: String, value: String, source: String): List<ProfileAttribute> {
        if (profileIds.isEmpty() || value.isBlank()) return emptyList()
        val normalized = value.lowercase().trim()
        // One UPDATE per profile: the repository query is scoped to a single profile (KSP cannot mix a
        // collection param with the other primitives). A principal almost always has one profile.
        val updated = profileIds.distinct().flatMap { profileAttributeRepository.markVerified(typeId, it, key, normalized, source) }
        // Bust the per-profile attribute cache for every profile we touched so the freshly-verified flag
        // is visible immediately rather than after TTL.
        updated.map { it.profile }.distinct().forEach { profileAttributesById.remove(it) }
        updated.map { it.profile }.distinct().forEach { ProfileAttributesVerified(it, typeId, source).dispatch() }
        return updated
    }

    override suspend fun setVerificationToken(typeId: String, profileId: UUID, key: String, value: String, token: String, origin: String?): List<ProfileAttribute> {
        if (value.isBlank()) return emptyList()
        val updated = profileAttributeRepository.setVerificationToken(typeId, profileId, key, value.lowercase().trim(), token, origin)
        updated.map { it.profile }.distinct().forEach { profileAttributesById.remove(it) }
        return updated
    }

    override suspend fun getByVerificationToken(token: String): ProfileAttribute? {
        if (token.isBlank()) return null
        return profileAttributeRepository.getByVerificationToken(token)
    }

    override suspend fun verifyByToken(token: String, source: String): List<ProfileAttribute> {
        if (token.isBlank()) return emptyList()
        val updated = profileAttributeRepository.verifyByToken(token, source)
        updated.map { it.profile }.distinct().forEach { profileAttributesById.remove(it) }
        // A token can verify attributes of multiple types; emit one event per (profile, type) pair.
        updated.map { it.profile to it.typeId }.distinct().forEach { (profileId, typeId) ->
            ProfileAttributesVerified(profileId, typeId, source).dispatch()
        }
        return updated
    }

    override suspend fun addAttributesToBatch(batch: Batch<UUID, List<ProfileAttribute>>) {
        profileAttributesById.addToBatch(batch)
    }

    override suspend fun getAttributesByProfileWithFilter(profileId: UUID, filter: ProfileAttributesFilterInput): List<ProfileAttribute> {
        val allAttributes = getAttributesByProfile(profileId)
        return filter.filterProfileAttributes(allAttributes)
    }

    fun ProfileAttributesFilterInput.filterProfileAttributes(profileAttributes: List<ProfileAttribute>): List<ProfileAttribute> {
        return profileAttributes.filter { attribute ->
            // Filter by visibility if specified
            if (visibility != null && attribute.visibility != visibility) {
                return@filter false
            }

            // Filter by confidence if specified
            if (confidence != null && attribute.confidence != confidence) {
                return@filter false
            }

            // Filter by priority if specified
            if (priority != null && attribute.priority != priority) {
                return@filter false
            }

            // Filter by source if specified
            if (source != null && attribute.source != source) {
                return@filter false
            }

            // Filter by typeId if specified
            if (typeId != null && attribute.typeId != typeId) {
                return@filter false
            }

            true
        }
    }

    override suspend fun addAttributes(profileId: UUID, inputs: List<ProfileAttributeInput>, allowProtected: Boolean): List<ProfileAttribute> {
        // A profile may hold at most ONE `bosca.profiles.email` attribute (the one verification/identity is
        // anchored to). Reject anything that would create a second — a new email input when one already
        // exists, or two new ones in the same batch. Editing the existing one is fine (it doesn't add).
        val newEmails = inputs.count { it.typeId == "bosca.profiles.email" && it.id == UUID.NIL }
        if (newEmails > 0) {
            val existingEmails = getAttributesByProfile(profileId).count { it.typeId == "bosca.profiles.email" }
            require(existingEmails + newEmails <= 1) { "a profile may have only one bosca.profiles.email attribute" }
        }
        val attributes = inputs.map { input ->
            val type = attributeTypeRepository.getById(input.typeId) ?: error("missing profile attribute type: ${input.typeId}")
            if (type.protected && !allowProtected) {
                throw SecurityException("profile attribute type '${input.typeId}' is protected and may only be set by administrators")
            }
            ProfileAttribute(
                id = input.id,
                profile = profileId,
                typeId = input.typeId,
                visibility = input.visibility,
                confidence = input.confidence,
                priority = input.priority,
                source = input.source,
                attributes = input.attributes,
                metadataId = input.metadataId,
                expires = input.expiration
            )
        }
        val result = attributes.map {
            if (it.id == UUID.NIL) profileAttributeRepository.add(it) else editPreservingProof(it)
        }
        profileAttributesById.remove(profileId)
        val (added, updated) = attributes.zip(result).partition { (input, _) -> input.id == UUID.NIL }
        if (added.isNotEmpty()) {
            val rows = added.map { it.second }
            ProfileAttributesAdded(profileId, rows.map { it.id }, rows.map { it.typeId }.distinct()).dispatch()
        }
        if (updated.isNotEmpty()) {
            val rows = updated.map { it.second }
            ProfileAttributesUpdated(profileId, rows.map { it.id }, rows.map { it.typeId }.distinct()).dispatch()
        }
        return result
    }

    /**
     * Edits an attribute, stripping its verification when the PROVEN value of a VERIFIED attribute actually
     * changes. The client edit path is NOT a proof-of-control channel, so a previously-verified value that is
     * edited must drop back to unverified (losing source + any pending token) — otherwise a user could
     * launder verification onto an arbitrary value (e.g. a victim's email) by editing a verified attribute.
     *
     * "Changed" is judged on the type's verifiable value key (normalized the same way verification matched it),
     * NOT the whole JSON blob — so editing an unrelated sibling key keeps proof, and a case/whitespace-only
     * change is not a real change. This is the same predicate the verification framework uses to decide
     * re-verification, so the two never disagree. A never-verified attribute keeps its state.
     */
    private suspend fun editPreservingProof(updated: ProfileAttribute): ProfileAttribute {
        val existing = profileAttributeRepository.getById(updated.id)
        val edited = profileAttributeRepository.edit(updated)
        if (existing == null || !existing.verified) return edited
        val valueKey = verifiableValueKey(updated.typeId)
        val proofStillValid = if (valueKey != null) {
            existing.getAttributeString(valueKey)?.lowercase()?.trim() == updated.getAttributeString(valueKey)?.lowercase()?.trim()
        } else {
            // Verified but type is unregistered (shouldn't happen — only registered types get verified): be
            // conservative and treat any JSON change as a value change.
            existing.attributes == updated.attributes
        }
        return if (proofStillValid) edited else profileAttributeRepository.clearVerification(updated.id)
    }

    /** The verifiable value key for [typeId] (e.g. "email" for `bosca.profiles.email`), or null if the type is not a registered [VerifiableAttributeType]. */
    @OptIn(InternalDI::class)
    private suspend fun verifiableValueKey(typeId: String): String? =
        ProviderRegistry.findAll(VerifiableAttributeType::class).map { it.get() }.firstOrNull { it.typeId == typeId }?.valueKey

    override suspend fun deleteAttribute(attributeId: UUID): UUID {
        val attribute = profileAttributeRepository.deleteById(attributeId) ?: return UUID.NIL
        profileAttributesById.remove(attribute.profile)
        ProfileAttributeDeleted(attribute.profile, attribute.id, attribute.typeId).dispatch()
        return attribute.profile
    }

    override suspend fun deleteAttribute(profileId: UUID, attributeId: UUID): UUID {
        val attribute = profileAttributeRepository.getById(attributeId)
        if (attribute?.profile != profileId) error("Profile attribute with id $attributeId not found")
        profileAttributeRepository.deleteById(attributeId)
        profileAttributesById.remove(attribute.profile)
        ProfileAttributeDeleted(attribute.profile, attribute.id, attribute.typeId).dispatch()
        return attribute.profile
    }
}
