package bosca.profile.guide.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.profile.guide.model.ProfileGuideHistory
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@Repository
interface ProfileGuideHistoryRepository {

    @Query("INSERT INTO profile_guide_history (profile_id, metadata_id, version, attributes, completed) VALUES (:profileId, :metadataId, :version, :attributes, now())")
    suspend fun add(profileId: UUID, metadataId: UUID, version: Int, attributes: JsonElement?)

    @Query("SELECT * FROM profile_guide_history WHERE profile_id = :profileId ORDER BY completed DESC LIMIT :limit OFFSET :offset")
    suspend fun findByProfileId(profileId: UUID, limit: Int, offset: Long): List<ProfileGuideHistory>

    @Query("SELECT COUNT(*) FROM profile_guide_history WHERE profile_id = :profileId")
    suspend fun countByProfileId(profileId: UUID): Long

    @Query("SELECT * FROM profile_guide_history WHERE profile_id = :profileId AND metadata_id = :metadataId AND version = :version ORDER BY completed DESC LIMIT :limit OFFSET :offset")
    suspend fun findByProfileAndMetadata(
        profileId: UUID,
        metadataId: UUID,
        version: Int,
        limit: Int,
        offset: Long
    ): List<ProfileGuideHistory>

    @Query("SELECT COUNT(*) FROM profile_guide_history WHERE profile_id = :profileId AND metadata_id = :metadataId AND version = :version")
    suspend fun countByProfileAndMetadata(profileId: UUID, metadataId: UUID, version: Int): Long
}
