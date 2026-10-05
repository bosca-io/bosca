package bosca.profile.bookmark.service

import bosca.profile.bookmark.model.ProfileBookmark
import bosca.serialization.UUID
import bosca.service.Service
import kotlinx.serialization.json.JsonElement

/**
 * Service for managing profile bookmarks on content metadata and collections.
 *
 * Bookmarks allow a profile to save references to specific content items (identified by
 * metadata ID and version) or collections for later retrieval. Each bookmark can carry
 * optional JSON attributes for additional context (e.g., notes, tags).
 */
interface ProfileBookmarkService : Service {

    /**
     * Retrieves a paginated list of bookmarks belonging to a profile.
     *
     * @param profileId the UUID of the profile whose bookmarks should be fetched
     * @param limit the maximum number of bookmarks to return (defaults to 10)
     * @param offset the number of bookmarks to skip for pagination (defaults to 0)
     * @return the list of [ProfileBookmark] instances for the specified profile
     */
    suspend fun getBookmarks(profileId: UUID, limit: Int = 10, offset: Int = 0): List<ProfileBookmark>

    /**
     * Returns the total number of bookmarks belonging to a profile.
     *
     * @param profileId the UUID of the profile to count bookmarks for
     * @return the total bookmark count
     */
    suspend fun getBookmarkCount(profileId: UUID): Long

    /**
     * Retrieves a specific bookmark for a profile targeting a metadata item at a given version.
     *
     * @param profileId the UUID of the profile that owns the bookmark
     * @param metadataId the UUID of the bookmarked metadata item
     * @param metadataVersion the version of the bookmarked metadata item
     * @return the matching [ProfileBookmark], or `null` if no such bookmark exists
     */
    suspend fun getBookmark(profileId: UUID, metadataId: UUID, metadataVersion: Int): ProfileBookmark?

    /**
     * Retrieves a specific bookmark for a profile targeting a collection.
     *
     * @param profileId the UUID of the profile that owns the bookmark
     * @param collectionId the UUID of the bookmarked collection
     * @return the matching [ProfileBookmark], or `null` if no such bookmark exists
     */
    suspend fun getBookmark(profileId: UUID, collectionId: UUID): ProfileBookmark?

    /**
     * Creates a bookmark for the specified profile. The bookmark must reference either a
     * metadata item (via [metadataId] and [metadataVersion]) or a [collectionId].
     *
     * @param profileId the UUID of the profile creating the bookmark
     * @param metadataId the UUID of the metadata item to bookmark, or `null` if bookmarking a collection
     * @param metadataVersion the version of the metadata item, or `null` if bookmarking a collection
     * @param collectionId the UUID of the collection to bookmark, or `null` if bookmarking metadata
     * @param attributes optional JSON attributes to store alongside the bookmark
     */
    suspend fun addBookmark(
        profileId: UUID,
        metadataId: UUID? = null,
        metadataVersion: Int? = null,
        collectionId: UUID? = null,
        attributes: JsonElement? = null
    )

    /**
     * Removes a bookmark for the specified profile. The target must be identified by either a
     * metadata item (via [metadataId] and [metadataVersion]) or a [collectionId].
     *
     * @param profileId the UUID of the profile that owns the bookmark
     * @param metadataId the UUID of the bookmarked metadata item, or `null` if targeting a collection
     * @param metadataVersion the version of the bookmarked metadata item, or `null` if targeting a collection
     * @param collectionId the UUID of the bookmarked collection, or `null` if targeting metadata
     */
    suspend fun deleteBookmark(
        profileId: UUID,
        metadataId: UUID? = null,
        metadataVersion: Int? = null,
        collectionId: UUID? = null
    )
}
