package bosca.profile.rating.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.profile.rating.model.ProfileRating
import bosca.serialization.UUID

@Repository
interface ProfileRatingRepository {

    @Query("INSERT INTO profile_ratings (profile_id, collection_id, metadata_id, metadata_version, rating) VALUES (:profileId, :collectionId, :metadataId, :metadataVersion, :rating) RETURNING *")
    suspend fun add(rating: ProfileRating): ProfileRating

    /** Updates a persisted rating while preserving its identity and creation time. */
    @Query("UPDATE profile_ratings SET rating = :rating WHERE id = :id RETURNING *")
    suspend fun update(rating: ProfileRating): ProfileRating

    @Query("SELECT * FROM profile_ratings WHERE profile_id = :profileId ORDER BY created DESC")
    suspend fun findByProfileId(profileId: UUID): List<ProfileRating>

    @Query("SELECT * FROM profile_ratings WHERE profile_id = :profileId AND metadata_id = :metadataId AND metadata_version = :metadataVersion LIMIT 1")
    suspend fun findByProfileAndMetadata(profileId: UUID, metadataId: UUID, metadataVersion: Int): ProfileRating?

    @Query("SELECT * FROM profile_ratings WHERE profile_id = :profileId AND collection_id = :collectionId LIMIT 1")
    suspend fun findByProfileAndCollection(profileId: UUID, collectionId: UUID): ProfileRating?

    @Query("SELECT AVG(rating) FROM profile_ratings WHERE metadata_id = :metadataId AND metadata_version = :metadataVersion")
    suspend fun getAverageRating(metadataId: UUID, metadataVersion: Int): Double

    @Query("SELECT AVG(rating) FROM profile_ratings WHERE collection_id = :collectionId")
    suspend fun getAverageRating(collectionId: UUID): Double
}
