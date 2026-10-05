package bosca.profile.guide.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.profile.guide.model.GuideProgressStatistics
import bosca.profile.guide.model.ProfileGuideProgress
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@Repository
interface ProfileGuideProgressRepository {

    @Query("INSERT INTO profile_guide_progress (profile_id, metadata_id, version, attributes) VALUES (:profileId, :metadataId, :version, :attributes) returning *")
    suspend fun add(progress: ProfileGuideProgress): ProfileGuideProgress

    @Query("SELECT * FROM profile_guide_progress WHERE profile_id = :profileId ORDER BY modified DESC LIMIT :limit OFFSET :offset")
    suspend fun findByProfileId(profileId: UUID, limit: Int, offset: Long): List<ProfileGuideProgress>

    @Query("SELECT * FROM profile_guide_progress WHERE profile_id = :profileId AND metadata_id = :metadataId ORDER BY modified DESC, version DESC LIMIT :limit OFFSET :offset")
    suspend fun findByProfileAndMetadataId(
        profileId: UUID,
        metadataId: UUID,
        limit: Int,
        offset: Long,
    ): List<ProfileGuideProgress>

    @Query("SELECT COUNT(*) FROM profile_guide_progress WHERE profile_id = :profileId")
    suspend fun countByProfileId(profileId: UUID): Long

    @Query("SELECT COUNT(*) FROM profile_guide_progress WHERE profile_id = :profileId AND metadata_id = :metadataId")
    suspend fun countByProfileAndMetadataId(profileId: UUID, metadataId: UUID): Long

    @Query("SELECT * FROM profile_guide_progress WHERE profile_id = :profileId AND metadata_id = :metadataId AND version = :version LIMIT 1")
    suspend fun findByProfileAndMetadata(profileId: UUID, metadataId: UUID, version: Int): ProfileGuideProgress?

    @Query("""
        SELECT profile_id
        FROM profile_guide_progress
        WHERE metadata_id = :metadataId
        GROUP BY profile_id
        ORDER BY MAX(modified) DESC, profile_id
        LIMIT :limit OFFSET :offset
    """)
    suspend fun findProfileIdsByMetadataId(metadataId: UUID, limit: Int, offset: Long): List<UUID>

    @Query("SELECT * FROM profile_guide_progress WHERE metadata_id = :metadataId AND profile_id = ANY(:profileIds) ORDER BY modified DESC, profile_id, version DESC")
    suspend fun findByMetadataIdAndProfileIds(
        metadataId: UUID,
        profileIds: List<UUID>,
    ): List<ProfileGuideProgress>

    @Query("""
        SELECT
            COUNT(*) FILTER (WHERE progression.is_active) AS active_progressions,
            COUNT(DISTINCT progression.profile_id) FILTER (WHERE progression.is_active) AS active_profiles,
            COUNT(*) FILTER (WHERE NOT progression.is_active) AS historical_progressions,
            COUNT(*) FILTER (WHERE NOT progression.is_active AND progression.completed IS NOT NULL) AS completions,
            COUNT(*) AS total_progressions,
            COUNT(DISTINCT progression.profile_id) AS unique_profiles
        FROM (
            SELECT profile_id, TRUE AS is_active, NULL::timestamptz AS completed
            FROM profile_guide_progress
            WHERE metadata_id = :metadataId
            UNION ALL
            SELECT profile_id, FALSE AS is_active, completed
            FROM profile_guide_history
            WHERE metadata_id = :metadataId
        ) progression
    """)
    suspend fun statistics(metadataId: UUID): GuideProgressStatistics

    @Query("SELECT * FROM profile_guide_progress WHERE profile_id = :profileId AND metadata_id = :metadataId AND version = :version limit 1 for update")
    suspend fun getProgressForUpdate(profileId: UUID, metadataId: UUID, version: Int): ProfileGuideProgress?

    @Query("""
        INSERT INTO profile_guide_progress as p (profile_id, metadata_id, version, attributes, completed_step_ids) 
        VALUES (:profileId, :metadataId, :version, :attributes, ARRAY[:stepId]::bigint[]) 
        ON CONFLICT (profile_id, metadata_id, version) 
        DO UPDATE SET modified = now(), attributes = coalesce(p.attributes, '{}'::jsonb) || :attributes, completed_step_ids = array_append(p.completed_step_ids, :stepId) 
        WHERE NOT (p.completed_step_ids @> ARRAY[:stepId]::bigint[]) 
        RETURNING *
    """)
    suspend fun addStepProgress(profileId: UUID, metadataId: UUID, version: Int, stepId: Long, attributes: JsonElement): ProfileGuideProgress?

    @Query("""
        INSERT INTO profile_guide_progress as p (profile_id, metadata_id, version, attributes, completed_step_ids) 
        VALUES (:profileId, :metadataId, :version, :attributes, '{}'::bigint[]) 
        ON CONFLICT (profile_id, metadata_id, version) 
        DO UPDATE SET modified = now(), attributes = coalesce(p.attributes, '{}'::jsonb) || :attributes 
        RETURNING *
    """)
    suspend fun addProgress(profileId: UUID, metadataId: UUID, version: Int, attributes: JsonElement): ProfileGuideProgress?

    @Query("DELETE FROM profile_guide_progress WHERE profile_id = :profileId AND metadata_id = :metadataId AND version = :version")
    suspend fun delete(profileId: UUID, metadataId: UUID, version: Int)
}
