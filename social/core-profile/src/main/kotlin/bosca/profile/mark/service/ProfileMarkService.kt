package bosca.profile.mark.service

import bosca.profile.mark.model.ProfileMark
import bosca.serialization.UUID
import bosca.service.Service
import kotlinx.serialization.json.JsonElement

/**
 * Service for managing profile marks on content metadata items and collections.
 *
 * Marks are lightweight annotations that a profile places on content, similar to
 * "likes" or "flags." Each mark can target either a metadata item (at a specific version)
 * or a collection, and can carry optional JSON attributes for additional context.
 * Unlike bookmarks, marks are identified by a numeric ID and a profile can place
 * multiple marks on the same content item.
 */
interface ProfileMarkService : Service {

    /**
     * Retrieves a paginated list of all marks belonging to a profile, regardless of target.
     *
     * @param profileId the UUID of the profile whose marks should be fetched
     * @param limit the maximum number of marks to return (defaults to 10)
     * @param offset the number of marks to skip for pagination (defaults to 0)
     * @return the list of [ProfileMark] instances for the profile
     */
    suspend fun getMarks(profileId: UUID, limit: Int = 10, offset: Int = 0): List<ProfileMark>

    /**
     * Retrieves a paginated list of marks that a profile has placed on a specific
     * metadata item at a given version.
     *
     * @param profileId the UUID of the profile
     * @param metadataId the UUID of the target metadata item
     * @param metadataVersion the version of the target metadata item
     * @param limit the maximum number of marks to return
     * @param offset the number of marks to skip for pagination
     * @return the list of [ProfileMark] instances matching the criteria
     */
    suspend fun getMarks(
        profileId: UUID,
        metadataId: UUID,
        metadataVersion: Int,
        limit: Int,
        offset: Long
    ): List<ProfileMark>

    /**
     * Retrieves a paginated list of marks that a profile has placed on a specific collection.
     *
     * @param profileId the UUID of the profile
     * @param collectionId the UUID of the target collection
     * @param limit the maximum number of marks to return (defaults to 10)
     * @param offset the number of marks to skip for pagination (defaults to 0)
     * @return the list of [ProfileMark] instances matching the criteria
     */
    suspend fun getMarks(profileId: UUID, collectionId: UUID, limit: Int = 10, offset: Long = 0): List<ProfileMark>

    /**
     * Returns the total number of marks a profile has placed on a specific metadata item
     * at a given version.
     *
     * @param profileId the UUID of the profile
     * @param metadataId the UUID of the target metadata item
     * @param metadataVersion the version of the target metadata item
     * @return the mark count
     */
    suspend fun getMarkCount(profileId: UUID, metadataId: UUID, metadataVersion: Int): Long

    /**
     * Returns the total number of marks a profile has placed on a specific collection.
     *
     * @param profileId the UUID of the profile
     * @param collectionId the UUID of the target collection
     * @return the mark count
     */
    suspend fun getMarkCount(profileId: UUID, collectionId: UUID): Long

    /**
     * Returns the total number of marks belonging to a profile across all content.
     *
     * @param profileId the UUID of the profile
     * @return the total mark count for the profile
     */
    suspend fun getMarkCount(profileId: UUID): Long

    /**
     * Retrieves all marks that a profile has placed on any of the specified metadata items,
     * regardless of version. Useful for bulk-loading marks across multiple content items
     * (e.g., all steps in a guide) in a single query.
     *
     * @param profileId the UUID of the profile
     * @param metadataIds the list of metadata item UUIDs to fetch marks for
     * @return the list of [ProfileMark] instances matching any of the given metadata IDs
     */
    suspend fun getMarks(profileId: UUID, metadataIds: List<UUID>): List<ProfileMark>

    /**
     * Creates a mark for the specified profile. The mark must target either a metadata item
     * (via [metadataId] and [metadataVersion]) or a [collectionId].
     *
     * @param profileId the UUID of the profile creating the mark
     * @param metadataId the UUID of the metadata item to mark, or `null` if marking a collection
     * @param metadataVersion the version of the metadata item, or `null` if marking a collection
     * @param collectionId the UUID of the collection to mark, or `null` if marking metadata
     * @param attributes optional JSON attributes to store alongside the mark
     */
    suspend fun addMark(
        profileId: UUID,
        metadataId: UUID? = null,
        metadataVersion: Int? = null,
        collectionId: UUID? = null,
        attributes: JsonElement? = null
    )

    /**
     * Deletes a mark by its numeric identifier.
     *
     * @param id the numeric ID of the mark to delete
     */
    suspend fun deleteMark(id: Long, profileId: UUID)
}
