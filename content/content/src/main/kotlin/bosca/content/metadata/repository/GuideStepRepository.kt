package bosca.content.metadata.repository

import bosca.content.metadata.model.GuideStep
import bosca.content.metadata.model.GuideStepCount
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface GuideStepRepository {

    @Query("insert into guide_steps (metadata_id, version, step_metadata_id, step_metadata_version, sort) values (:metadataId, :version, :stepMetadataId, :stepMetadataVersion, :sort) returning *")
    suspend fun add(step: GuideStep): GuideStep

    @Query("select * from guide_steps where metadata_id = :id and version = :version order by sort")
    suspend fun getByMetadataIdAndVersion(id: UUID, version: Int): List<GuideStep>

    @Query("select * from guide_steps where metadata_id = :id and version = :version and id = :stepId")
    suspend fun getByMetadataIdAndVersionAndStep(id: UUID, version: Int, stepId: Long): GuideStep

    @Query("select * from guide_steps where metadata_id = :id and version = :version order by sort offset :offset limit :limit")
    suspend fun getByMetadataIdAndVersion(id: UUID, version: Int, offset: Int, limit: Int): List<GuideStep>

    @Query("select * from guide_steps where metadata_id = :id and version = :version order by sort offset :offset")
    suspend fun getByMetadataIdAndVersion(id: UUID, version: Int, offset: Int): List<GuideStep>

    @Query("select count(*) from guide_steps where metadata_id = :id and version = :version")
    suspend fun getCountByMetadataIdAndVersion(id: UUID, version: Int): Long

    @Query("select metadata_id, version, count(*) as count from guide_steps where metadata_id = any(:ids) group by metadata_id, version")
    suspend fun getCountByMetadataIds(ids: List<UUID>): List<GuideStepCount>

    @Query("select * from guide_steps where metadata_id = any(:ids)")
    suspend fun getByMetadataIds(ids: List<UUID>): List<GuideStep>

    @Query("update guide_steps set sort = :sort where metadata_id = :id and version = :version and id = :stepId")
    suspend fun setGuideStepSort(id: UUID, version: Int, stepId: Long, sort: Int)

    @Query("delete from guide_steps where metadata_id = :id and version = :version and id = :stepId")
    suspend fun deleteGuideStepByMetadataIdAndVersionAndStepId(id: UUID, version: Int, stepId: Long)
}
