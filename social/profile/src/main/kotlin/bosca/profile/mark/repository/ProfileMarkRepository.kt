package bosca.profile.mark.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.profile.mark.model.ProfileMark
import bosca.profile.mark.model.ProfileMetadataIdsQuery
import bosca.serialization.UUID

@Repository
interface ProfileMarkRepository {

    @Query("INSERT INTO profile_marks (profile_id, metadata_id, metadata_version, collection_id, attributes) VALUES (:profileId, :metadataId, :metadataVersion, :collectionId, :attributes) returning *")
    suspend fun add(mark: ProfileMark): ProfileMark

    @Query("SELECT * FROM profile_marks WHERE profile_id = :profileId ORDER BY created DESC LIMIT :limit OFFSET :offset")
    suspend fun findByProfileId(profileId: UUID, limit: Int, offset: Int): List<ProfileMark>

    @Query("SELECT COUNT(*) FROM profile_marks WHERE profile_id = :profileId")
    suspend fun countByProfileId(profileId: UUID): Long

    @Query("SELECT * FROM profile_marks WHERE profile_id = :profileId AND metadata_id = :metadataId AND metadata_version = :metadataVersion LIMIT :limit OFFSET :offset")
    suspend fun findByProfileAndMetadata(
        profileId: UUID,
        metadataId: UUID,
        metadataVersion: Int,
        limit: Int,
        offset: Long
    ): List<ProfileMark>

    @Query("SELECT COUNT(*) FROM profile_marks WHERE profile_id = :profileId AND metadata_id = :metadataId AND metadata_version = :metadataVersion")
    suspend fun countByProfileAndMetadata(profileId: UUID, metadataId: UUID, metadataVersion: Int): Long

    @Query("SELECT COUNT(*) FROM profile_marks WHERE profile_id = :profileId AND collection_id = :collectionId")
    suspend fun countByProfileAndCollection(profileId: UUID, collectionId: UUID): Long

    @Query("SELECT * FROM profile_marks WHERE profile_id = :profileId AND collection_id = :collectionId LIMIT :limit OFFSET :offset")
    suspend fun findByProfileAndCollection(
        profileId: UUID,
        collectionId: UUID,
        limit: Int,
        offset: Long
    ): List<ProfileMark>

    @Query("SELECT * FROM profile_marks WHERE profile_id = :profileId AND metadata_id = ANY(:metadataIds) ORDER BY created DESC")
    suspend fun findByProfileAndMetadataIds(query: ProfileMetadataIdsQuery): List<ProfileMark>

    @Query("delete from profile_marks where id = :id and profile_id = :profileId returning *")
    suspend fun deleteById(id: Long, profileId: UUID): ProfileMark?
}
