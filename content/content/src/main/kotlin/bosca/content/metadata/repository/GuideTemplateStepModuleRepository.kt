package bosca.content.metadata.repository

import bosca.content.metadata.model.GuideTemplateStepModule
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface GuideTemplateStepModuleRepository {

    @Query("insert into guide_template_step_modules (metadata_id, version, id, template_metadata_id, template_metadata_version, sort) values (:metadataId, :version, :id, :templateMetadataId, :templateMetadataVersion, :sort) returning *")
    suspend fun add(stepModule: GuideTemplateStepModule): GuideTemplateStepModule

    @Query("select * from guide_template_step_modules where metadata_id = :id and version = :version and step = :step order by sort")
    suspend fun getByMetadataIdAndVersionAndStep(id: UUID, version: Int, step: Long): List<GuideTemplateStepModule>

    @Query("select * from guide_template_step_modules where metadata_id = any(:ids)")
    suspend fun getByMetadataIds(ids: List<UUID>): List<GuideTemplateStepModule>

    @Query("select * from guide_template_step_modules where metadata_id = :id and version = :version and step = :step and id = :module order by sort")
    suspend fun getByMetadataIdAndVersionAndStepModule(id: UUID, version: Int, step: Long, module: Long): GuideTemplateStepModule

    @Query("select * from guide_template_step_modules where metadata_id = :id and version = :version and id = :module")
    suspend fun getByMetadataIdAndVersionAndModule(id: UUID, version: Int, module: Long): GuideTemplateStepModule?

    @Query("delete from guide_template_step_modules where metadata_id = :metadataId and version = :version and step = :step and id = :module")
    suspend fun deleteByMetadataIdAndVersionAndStepModule(metadataId: UUID, version: Int, step: Long, module: Long)

    @Query("update guide_template_step_modules set sort = :sort where id = :id")
    suspend fun setSort(id: Long, sort: Int)
}