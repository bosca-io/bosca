package bosca.profile.relationship.service

import bosca.profile.relationship.model.ProfileRelationship
import bosca.serialization.UUID
import bosca.service.Service
import kotlinx.serialization.json.JsonElement

/**
 * Service for managing typed relationships between profiles.
 *
 * Relationships are directional links between two profiles, categorized by a string type
 * (e.g., "friend", "parent", "mentor"). Each relationship can carry optional JSON attributes
 * for additional context. The relationship is identified by the ordered pair of profile IDs
 * together with the type.
 */
interface ProfileRelationshipService : Service {

    /** Returns one exact directional relationship, or `null` when it does not exist. */
    suspend fun getRelationship(profileId1: UUID, profileId2: UUID, type: String): ProfileRelationship?

    /**
     * Retrieves all relationships originating from a profile, optionally filtered by type.
     *
     * @param profileId the UUID of the relationship source profile
     * @param type an optional relationship type string to filter by; if `null`, all relationship types are returned
     * @return the list of [ProfileRelationship] instances whose first profile is [profileId]
     */
    suspend fun getRelationships(profileId: UUID, type: String? = null): List<ProfileRelationship>

    /**
     * Retrieves a deterministic page of relationships originating from a profile.
     *
     * @param profileId the UUID of the relationship source profile
     * @param type an optional relationship type string to filter by; if `null`, all relationship types are returned
     * @param offset the number of relationships to skip; must not be negative
     * @param limit the maximum number of relationships to return; must not be negative
     * @return the requested page of [ProfileRelationship] instances whose first profile is [profileId]
     */
    suspend fun getRelationships(
        profileId: UUID,
        type: String?,
        offset: Long,
        limit: Int,
    ): List<ProfileRelationship>

    /**
     * Creates a relationship between two profiles with the specified type.
     *
     * @param profileId1 the UUID of the first profile in the relationship
     * @param profileId2 the UUID of the second profile in the relationship
     * @param type the relationship type identifier (e.g., "friend", "parent")
     * @param attributes optional JSON attributes to associate with the relationship
     * @throws IllegalArgumentException when both profile IDs identify the same profile
     */
    suspend fun addRelationship(
        profileId1: UUID,
        profileId2: UUID,
        type: String,
        attributes: JsonElement? = null
    )

    /**
     * Removes a relationship between two profiles of the specified type.
     *
     * @param profileId1 the UUID of the first profile in the relationship
     * @param profileId2 the UUID of the second profile in the relationship
     * @param type the relationship type identifier to remove
     */
    suspend fun removeRelationship(
        profileId1: UUID,
        profileId2: UUID,
        type: String
    )
}
