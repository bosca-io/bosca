package bosca.content.metadata.repository

import bosca.content.metadata.model.GuideTemplateStep
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface GuideTemplateStepRepository {

    @Query("insert into guide_template_steps (metadata_id, version, template_metadata_id, template_metadata_version, sort) values (:metadataId, :version, :templateMetadataId, :templateMetadataVersion, :sort) returning *")
    suspend fun add(step: GuideTemplateStep): GuideTemplateStep

    @Query("select * from guide_template_steps where metadata_id = :id and version = :version order by sort")
    suspend fun getByMetadataIdAndVersion(id: UUID, version: Int): List<GuideTemplateStep>

    @Query("select * from guide_template_steps where metadata_id = any(:ids)")
    suspend fun getByMetadataIds(ids: List<UUID>): List<GuideTemplateStep>

    @Query("delete from guide_template_steps where metadata_id = :id and version = :version")
    suspend fun deleteByMetadataIdAndVersion(id: UUID, version: Int)

    @Query("select * from guide_template_steps where metadata_id = :id and version = :version and id = :step")
    suspend fun getByMetadataIdAndVersionAndStep(id: UUID, version: Int, step: Long): GuideTemplateStep?

    @Query("delete from guide_template_steps where metadata_id = :metadataId and version = :version and id = :step")
    suspend fun deleteByMetadataIdAndVersionAndStep(metadataId: UUID, version: Int, step: Long)

    @Query("update guide_template_steps set sort = :sort where id = :id")
    suspend fun setSort(id: Long, sort: Int)
}