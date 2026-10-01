package bosca.profile.profile.service

import bosca.graphql.Batch
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.attribute.model.ProfileAttributeInput
import bosca.profile.attribute.model.ProfileAttributeType
import bosca.profile.attribute.model.ProfileAttributeTypeInput
import bosca.profile.attribute.model.ProfileAttributesFilterInput
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.profile.model.ProfileInput
import bosca.security.model.PermissionService
import bosca.security.model.Principal
import bosca.serialization.UUID

/**
 * Service for managing user profiles and their associated attributes.
 *
 * Profiles represent individual entities in the system (generic users, organizations, or
 * child accounts as defined by [ProfileType]). Each profile can own typed attributes,
 * be linked to a security principal, and is subject to visibility and permission controls.
 *
 * This service consolidates profile CRUD operations with attribute type management and
 * per-profile attribute operations. It extends [PermissionService] to support entity-level
 * permission querying and batch permission loading.
 */
interface ProfileService : PermissionService<Profile, UUID> {

    /**
     * Retrieves a paginated list of all profiles regardless of type.
     *
     * @param offset the number of profiles to skip for pagination
     * @param limit the maximum number of profiles to return
     * @return the list of [Profile] instances
     */
    suspend fun getAll(offset: Long, limit: Int): List<Profile>

    /**
     * Retrieves a paginated list of profiles filtered by their [ProfileType].
     *
     * @param offset the number of profiles to skip for pagination
     * @param limit the maximum number of profiles to return
     * @param type the profile type to filter by (e.g., GENERIC, ORGANIZATION, CHILD)
     * @return the list of [Profile] instances matching the specified type
     */
    suspend fun getAllByType(offset: Long, limit: Int, type: ProfileType): List<Profile>

    /**
     * Retrieves multiple profiles by their identifiers in a single operation.
     *
     * @param ids the list of profile UUIDs to fetch
     * @return the list of matching [Profile] instances
     */
    suspend fun getAllByIds(ids: List<UUID>): List<Profile>

    /**
     * Retrieves a single profile by its identifier.
     *
     * @param id the UUID of the profile
     * @return the [Profile] matching the given [id]
     */
    suspend fun getById(id: UUID): Profile

    /**
     * Retrieves a profile by its canonical, human-readable slug.
     *
     * @param slug the exact profile slug to resolve
     * @return the matching [Profile], or `null` when the slug is not assigned to a profile
     */
    suspend fun getBySlug(slug: String): Profile?

    /**
     * Retrieves profiles whose names exactly match any supplied value, ignoring case.
     *
     * Profile names are not unique, so callers must explicitly handle multiple matches.
     *
     * @param names profile names to resolve
     * @return every profile whose name matches one of [names]
     */
    suspend fun getByNames(names: List<String>): List<Profile>

    /**
     * Retrieves all profiles associated with a given security principal. A principal
     * may own multiple profiles (e.g., a personal profile and an organization profile).
     *
     * @param principalId the UUID of the security principal
     * @return the list of [Profile] instances owned by the principal
     */
    suspend fun getByPrincipal(principalId: UUID): List<Profile>

    /**
     * Retrieves the primary profile for a given security principal. A principal may
     * own multiple profiles (e.g., a personal profile and an organization profile),
     * but only one is considered the primary. The selected profile is returned only when it is
     * active and owned by [principal]. If a primary profile is not set, the first active profile
     * returned by [getByPrincipal] is used.
     * @param principal the security principal
     * @return the primary [Profile] for the principal, or null if none exists
     */
    suspend fun getPrimaryProfile(principal: Principal): Profile?

    /**
     * Retrieves a paginated list of profiles whose linked principal is a member of a
     * named security group. Used by the collaboration / mentions UI to surface every
     * profile a mention can reach (e.g., the `messaging` group), independent of any
     * particular channel's roster.
     *
     * Results are deduplicated by profile id so a principal that joined the group via
     * multiple paths still only contributes one row.
     *
     * @param groupName the unique name of the security group to filter by
     * @param offset the number of profiles to skip for pagination
     * @param limit the maximum number of profiles to return
     * @return the list of [Profile] instances whose principal is in the named group
     */
    suspend fun getByGroupName(groupName: String, offset: Long, limit: Int): List<Profile>

    /**
     * Retrieves all profiles that have a **verified** email attribute matching the given address.
     *
     * Only proven (`verified = true`) email attributes match, so this resolves identity rather than mere
     * claims — a client-set squat attribute carrying someone else's address is ignored. It backs
     * `getPrincipalByEmail` (sign-up collision detection, forgot-password routing).
     *
     * @param email the email address to search for across verified profile attributes
     * @return the list of [Profile] instances whose verified attributes contain the specified email
     */
    suspend fun getProfilesByEmail(email: String): List<Profile>

    /**
     * Retrieves all registered attribute type definitions available for profiles.
     *
     * @return the complete list of [ProfileAttributeType] definitions
     */
    suspend fun getAttributeTypes(): List<ProfileAttributeType>

    /**
     * Retrieves all attributes associated with a specific profile.
     *
     * @param profileId the UUID of the profile whose attributes should be fetched
     * @return the list of [ProfileAttribute] instances belonging to the profile
     */
    suspend fun getAttributes(profileId: UUID): List<ProfileAttribute>

    /**
     * Populates a [Batch] with attribute lists keyed by profile UUID. Used for efficient
     * bulk loading of attributes for multiple profiles, typically for GraphQL DataLoader
     * integration.
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
     * Creates a new profile with the specified type. Optionally links the profile to
     * a security principal.
     *
     * @param input the profile specification including name, slug, visibility, and optional initial attributes
     * @param type the type of profile to create (GENERIC, ORGANIZATION, or CHILD)
     * @param principalId the UUID of the security principal to own this profile, or `null` for an unlinked profile
     * @param allowProtected whether the initial attributes may target protected attribute types
     * @return the newly created [Profile]
     */
    suspend fun add(input: ProfileInput, type: ProfileType, principalId: UUID? = null, allowProtected: Boolean = false): Profile

    /**
     * Updates an existing profile's properties (name, slug, visibility, attributes).
     *
     * @param id the UUID of the profile to modify
     * @param input the updated profile specification
     * @param allowProtected whether the supplied attributes may target protected attribute types
     * @return the modified [Profile]
     */
    suspend fun edit(id: UUID, input: ProfileInput, allowProtected: Boolean = false): Profile

    /**
     * Links a security principal to a profile, establishing ownership. This allows the
     * principal to authenticate as this profile and manage it.
     *
     * @param id the UUID of the profile to link
     * @param principalId the UUID of the security principal to assign as owner
     * @return the updated [Profile]
     */
    suspend fun setPrincipal(id: UUID, principalId: UUID): Profile

    /**
     * Removes the association between a profile and its owning principal. If the profile
     * is currently set as the principal's primary profile, that reference is also cleared. This is
     * an administrative identity removal, so durable profile cleanup removes the profile's domain
     * memberships and stale security-group projections after the transaction commits.
     *
     * @param id the UUID of the profile to unlink
     * @return the updated [Profile] with no principal association
     */
    suspend fun clearPrincipal(id: UUID): Profile

    /**
     * Associates a content collection with a profile. Used to give a profile a
     * personal collection for bookmarking, curating, or organizing content items.
     *
     * @param id the UUID of the profile to update
     * @param collectionId the UUID of the content collection to associate
     * @return the updated [Profile]
     */
    suspend fun setCollectionId(id: UUID, collectionId: UUID): Profile

    /**
     * Marks a profile as deleted — a reversible staging step ahead of a full [delete]. Sets the
     * profile's `deletedAt` timestamp, which excludes it from the search index on its next reindex.
     *
     * @param id the UUID of the profile to mark deleted
     * @return the updated [Profile] carrying its `deletedAt` timestamp
     */
    suspend fun markDeleted(id: UUID): Profile

    /**
     * Reverses [markDeleted] by clearing the profile's `deletedAt` timestamp.
     *
     * @param id the UUID of the profile to restore
     * @return the updated [Profile] with no `deletedAt`
     */
    suspend fun restore(id: UUID): Profile

    /**
     * Permanently deletes a profile by its identifier.
     *
     * @param id the UUID of the profile to delete
     */
    suspend fun delete(id: UUID)

    /**
     * Creates a new attribute type definition that can be used across all profiles.
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
     * Adds one or more attributes to the specified profile.
     *
     * Attributes whose type is protected may only be written when [allowProtected] is true;
     * otherwise the whole call fails with a security error. Callers on a user-facing path must
     * derive [allowProtected] from the caller's administrative capability, never from user input.
     *
     * @param profileId the UUID of the profile to attach attributes to
     * @param attributes the list of attribute specifications to create
     * @param allowProtected whether writes to protected attribute types are permitted
     * @return the list of newly created [ProfileAttribute] instances
     */
    suspend fun addAttributes(profileId: UUID, attributes: List<ProfileAttributeInput>, origin: String? = null, allowProtected: Boolean = false): List<ProfileAttribute>

    // ── Generic attribute-verification primitive (server-only; no GraphQL path sets `verified`) ───────

    /**
     * Marks the [typeId] attribute whose JSON [key] equals [value] across the given [profileIds] as
     * verified, recording [source] (e.g. `"email"`, `"google"`). Callers MUST have established proof of
     * control (a confirmed verification link or a provider assertion) before calling this. For email:
     * `markVerified("bosca.profiles.email", profileIds, "email", address, source)`.
     *
     * @return the attributes that were updated
     */
    suspend fun markVerified(typeId: String, profileIds: List<UUID>, key: String, value: String, source: String): List<ProfileAttribute>

    /**
     * Stamps a pending verification [token] onto the [profileId]'s (still-unverified) [typeId] attribute
     * whose JSON [key] equals [value], so a later [verifyByToken] can mark exactly that value verified.
     *
     * @param origin the raw web origin the request came from, persisted so the email link routes back to
     *   the right host (multi-host); validated against the allow-list when the link is built.
     * @return the attributes that were stamped (empty if no matching unverified attribute exists)
     */
    suspend fun setVerificationToken(typeId: String, profileId: UUID, key: String, value: String, token: String, origin: String? = null): List<ProfileAttribute>

    /** The (still-unredeemed) attribute carrying the pending verification [token], or null if none. */
    suspend fun getByVerificationToken(token: String): ProfileAttribute?

    /**
     * Redeems a verification [token]: marks the attribute(s) (of ANY type) carrying it verified, records
     * [source], and clears the token. Resolving by the globally-unique token pins the mark to the proven
     * attribute rather than inferring it.
     *
     * @return the attributes that were verified (empty if the token matched nothing)
     */
    suspend fun verifyByToken(token: String, source: String): List<ProfileAttribute>

    /**
     * Deletes a profile attribute by its own identifier, without requiring the owning profile ID.
     *
     * @param attributeId the UUID of the attribute to delete
     */
    suspend fun deleteAttribute(attributeId: UUID)

    /**
     * Deletes a profile attribute, scoped to a specific profile for additional validation
     * that the attribute belongs to the given profile.
     *
     * @param profileId the UUID of the profile that owns the attribute
     * @param attributeId the UUID of the attribute to delete
     */
    suspend fun deleteAttributeFromProfile(profileId: UUID, attributeId: UUID)
}
