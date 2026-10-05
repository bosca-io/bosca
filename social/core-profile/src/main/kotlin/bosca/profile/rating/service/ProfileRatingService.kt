package bosca.profile.rating.service

import bosca.profile.rating.model.ProfileRating
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Service for managing profile ratings on content metadata items and collections.
 *
 * Ratings allow profiles to assign a numeric score to content. Each profile can rate
 * a given metadata item (at a specific version) or collection at most once. The service
 * also supports computing average ratings across all profiles for a content item.
 */
interface ProfileRatingService : Service {

    /**
     * Retrieves all ratings submitted by a specific profile.
     *
     * @param profileId the UUID of the profile whose ratings should be fetched
     * @return the list of [ProfileRating] instances submitted by the profile
     */
    suspend fun getRatingsByProfile(profileId: UUID): List<ProfileRating>

    /**
     * Retrieves a specific rating that a profile has submitted for a metadata item
     * at a given version.
     *
     * @param profileId the UUID of the profile that submitted the rating
     * @param metadataId the UUID of the rated metadata item
     * @param metadataVersion the version of the rated metadata item
     * @return the matching [ProfileRating], or `null` if the profile has not rated this item
     */
    suspend fun getRating(
        profileId: UUID,
        metadataId: UUID,
        metadataVersion: Int
    ): ProfileRating?

    /**
     * Retrieves a specific rating that a profile has submitted for a collection.
     *
     * @param profileId the UUID of the profile that submitted the rating
     * @param collectionId the UUID of the rated collection
     * @return the matching [ProfileRating], or `null` if the profile has not rated this collection
     */
    suspend fun getRating(profileId: UUID, collectionId: UUID): ProfileRating?

    /**
     * Computes the average rating across all profiles for a specific metadata item at
     * a given version.
     *
     * @param metadataId the UUID of the metadata item
     * @param metadataVersion the version of the metadata item
     * @return the average rating as a [Double], or `null` if no ratings exist for this item
     */
    suspend fun getAverageRating(metadataId: UUID, metadataVersion: Int): Double?

    /**
     * Computes the average rating across all profiles for a specific collection.
     *
     * @param collectionId the UUID of the collection
     * @return the average rating as a [Double], or `null` if no ratings exist for this collection
     */
    suspend fun getAverageRating(collectionId: UUID): Double?

    /**
     * Submits a new rating from a profile for a content item. The rating must target either
     * a metadata item (via [metadataId] and [metadataVersion]) or a [collectionId].
     *
     * @param profileId the UUID of the profile submitting the rating
     * @param rating the numeric rating value
     * @param metadataId the UUID of the metadata item to rate, or `null` if rating a collection
     * @param metadataVersion the version of the metadata item, or `null` if rating a collection
     * @param collectionId the UUID of the collection to rate, or `null` if rating metadata
     * @return the newly created [ProfileRating]
     */
    suspend fun addRating(
        profileId: UUID,
        rating: Int,
        metadataId: UUID? = null,
        metadataVersion: Int? = null,
        collectionId: UUID? = null
    ): ProfileRating

    /**
     * Updates an existing rating that a profile has previously submitted for a content item.
     * The target must be identified by either a metadata item (via [metadataId] and
     * [metadataVersion]) or a [collectionId].
     *
     * @param profileId the UUID of the profile whose rating should be updated
     * @param rating the new numeric rating value
     * @param metadataId the UUID of the rated metadata item, or `null` if targeting a collection
     * @param metadataVersion the version of the rated metadata item, or `null` if targeting a collection
     * @param collectionId the UUID of the rated collection, or `null` if targeting metadata
     * @return the updated [ProfileRating], or `null` if no existing rating was found to update
     */
    suspend fun updateRating(
        profileId: UUID,
        rating: Int,
        metadataId: UUID? = null,
        metadataVersion: Int? = null,
        collectionId: UUID? = null
    ): ProfileRating?
}
