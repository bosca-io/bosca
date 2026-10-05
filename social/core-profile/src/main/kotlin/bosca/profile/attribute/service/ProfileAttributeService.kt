package bosca.profile.attribute.service

import bosca.graphql.Batch
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.attribute.model.ProfileAttributeInput
import bosca.profile.attribute.model.ProfileAttributeType
import bosca.profile.attribute.model.ProfileAttributeTypeInput
import bosca.profile.attribute.model.ProfileAttributesFilterInput
import bosca.serialization.UUID
import bosca.service.Service
import kotlinx.serialization.json.JsonElement

/**
 * Service for managing profile attributes and their associated type definitions.
 *
 * Profile attributes represent typed key-value data attached to profiles, such as
 * preferences, contact information, or behavioral traits. Each attribute has a type
 * (defined by [ProfileAttributeType]), a visibility level, a confidence score, and
 * a priority ranking.
 *
 * This service handles both the attribute type schema (CRUD for [ProfileAttributeType])
 * and the attribute instances themselves (CRUD for [ProfileAttribute]).
 */
interface ProfileAttributeService : Service {

    /**
     * Retrieves all registered attribute type definitions.
     *
     * @return the complete list of [ProfileAttributeType] definitions available in the system
     */
    suspend fun getAllAttributeTypes(): List<ProfileAttributeType>

    /**
     * Retrieves a single attribute type definition by its identifier.
     *
     * @param id the unique string identifier of the attribute type
     * @return the matching [ProfileAttributeType], or `null` if no type exists with the given [id]
     */
    suspend fun getAttributeTypeById(id: String): ProfileAttributeType?

    /**
     * Creates a new attribute type definition from the provided input.
     *
     * @param input the attribute type specification including id, name, description, visibility, and protection status
     * @return the newly created [ProfileAttributeType]
     */
    suspend fun addAttributeType(input: ProfileAttributeTypeInput): ProfileAttributeType

    /**
     * Updates an existing attribute type definition. The [input]'s id field is used
     * to locate the type to modify.
     *
     * @param input the updated attribute type specification
     * @return the modified [ProfileAttributeType]
     */
    suspend fun editAttributeType(input: ProfileAttributeTypeInput): ProfileAttributeType

    /**
     * Deletes an attribute type definition by its identifier.
     *
     * @param id the unique string identifier of the attribute type to remove
     */
    suspend fun deleteAttributeType(id: String)

    /**
     * Retrieves a single attribute by its own identifier, independent of the owning profile.
     *
     * @param attributeId the UUID of the attribute to fetch
     * @return the matching [ProfileAttribute], or `null` if none exists with the given [attributeId]
     */
    suspend fun getById(attributeId: UUID): ProfileAttribute?

    /** All attributes of a given type across profiles (the Personalization Signal backfill lookup). */
    suspend fun getByTypeId(typeId: String): List<ProfileAttribute>

    /**
     * Sets the cached Personalization Signals on an attribute — a `List<PersonalizationSignal>`
     * as opaque JSON, or null to clear. Server-only; does not touch the attribute's value or `verified`.
     */
    suspend fun setSignals(attributeId: UUID, signals: JsonElement?)

    /**
     * Retrieves all attributes associated with a specific profile.
     *
     * @param profileId the UUID of the profile whose attributes should be fetched
     * @return the list of [ProfileAttribute] instances belonging to the specified profile
     */
    suspend fun getAttributesByProfile(profileId: UUID): List<ProfileAttribute>

    /**
     * Looks up profile identifiers that have a **verified** email attribute matching the given address.
     *
     * Only attributes whose control has been proven (`verified = true`) match — this is the identity
     * lookup behind `getPrincipalByEmail`, so a client-set squat attribute must never resolve. To match an
     * unverified address (e.g. for general search) a separate, clearly-named method should be added rather
     * than relaxing this one.
     *
     * @param email the email address to search for across verified profile attributes
     * @return a list of profile UUIDs whose verified attributes contain the specified email
     */
    suspend fun getProfileIdsByEmail(email: String): List<UUID>

    // ── Generic attribute-verification primitive ──────────────────────────────────────────────────────
    // Verify ANY attribute type, identifying the value by its JSON [key] (e.g. typeId
    // "bosca.profiles.email" + key "email"). All three are server-only — there is no GraphQL mutation that
    // sets `verified` — so callers must establish proof of control before invoking them.

    /**
     * Marks the [typeId] attribute whose JSON [key] equals [value] across the given [profileIds] as
     * verified, recording [source] (e.g. `"email"`, `"google"`) and clearing any pending token.
     *
     * @return the attributes that were updated
     */
    suspend fun markVerified(typeId: String, profileIds: List<UUID>, key: String, value: String, source: String): List<ProfileAttribute>

    /**
     * Stamps a pending verification [token] onto the [profileId]'s (still-unverified) [typeId] attribute
     * whose JSON [key] equals [value]. The token is later redeemed by [verifyByToken] to mark exactly this
     * attribute verified.
     *
     * @param origin the raw web origin the request came from, persisted so the email link routes back to
     *   the right host (multi-host); validated against the allow-list when the link is built.
     * @return the attributes that were stamped (empty if no matching unverified attribute exists)
     */
    suspend fun setVerificationToken(typeId: String, profileId: UUID, key: String, value: String, token: String, origin: String? = null): List<ProfileAttribute>

    /** The (still-unredeemed) attribute carrying the pending verification [token], or null if none. */
    suspend fun getByVerificationToken(token: String): ProfileAttribute?

    /**
     * Redeems a verification [token]: marks every attribute (of ANY type) carrying it verified, records
     * [source], and clears the token (single-use). Resolving by the globally-unique token pins the mark to
     * the exact proven attribute.
     *
     * @return the attributes that were verified (empty if the token matched nothing)
     */
    suspend fun verifyByToken(token: String, source: String): List<ProfileAttribute>

    /**
     * Populates a [Batch] with attribute lists keyed by profile UUID. Used to efficiently
     * load attributes for multiple profiles in a single operation, typically for GraphQL
     * DataLoader integration.
     *
     * @param batch the batch to populate, keyed by profile UUID with values of attribute lists
     */
    suspend fun addAttributesToBatch(batch: Batch<UUID, List<ProfileAttribute>>)

    /**
     * Retrieves attributes for a profile that match the specified filter criteria, such as
     * attribute type, visibility, confidence threshold, priority, or source.
     *
     * @param profileId the UUID of the profile whose attributes should be fetched
     * @param filter the filter criteria to apply when selecting attributes
     * @return the filtered list of [ProfileAttribute] instances
     */
    suspend fun getAttributesByProfileWithFilter(profileId: UUID, filter: ProfileAttributesFilterInput): List<ProfileAttribute>

    /**
     * Adds one or more attributes to the specified profile.
     *
     * Attributes whose [ProfileAttributeType] is protected may only be written when [allowProtected]
     * is true; otherwise the whole call fails with a security error. Callers on a user-facing path
     * must derive [allowProtected] from the caller's administrative capability, never from user input.
     *
     * @param profileId the UUID of the profile to attach attributes to
     * @param inputs the list of attribute specifications to create
     * @param allowProtected whether writes to protected attribute types are permitted
     * @return the list of newly created [ProfileAttribute] instances
     */
    suspend fun addAttributes(profileId: UUID, inputs: List<ProfileAttributeInput>, allowProtected: Boolean = false): List<ProfileAttribute>

    /**
     * Deletes a profile attribute by its own identifier, without requiring the owning profile ID.
     *
     * @param attributeId the UUID of the attribute to delete
     * @return the UUID of the deleted attribute
     */
    suspend fun deleteAttribute(attributeId: UUID): UUID

    /**
     * Deletes a profile attribute, scoped to a specific profile for additional validation.
     *
     * @param profileId the UUID of the profile that owns the attribute
     * @param attributeId the UUID of the attribute to delete
     * @return the UUID of the deleted attribute
     */
    suspend fun deleteAttribute(profileId: UUID, attributeId: UUID): UUID
}
