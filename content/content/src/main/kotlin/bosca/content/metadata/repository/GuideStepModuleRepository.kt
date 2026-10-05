package bosca.content.metadata.repository

import bosca.content.metadata.model.GuideStepModule
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface GuideStepModuleRepository {

    @Query("insert into guide_step_modules (metadata_id, version, step, module_metadata_id, module_metadata_version, sort) values (:metadataId, :version, :step, :moduleMetadataId, :moduleMetadataVersion, :sort) returning *")
    suspend fun add(stepModule: GuideStepModule): GuideStepModule

    @Query("select * from guide_step_modules where metadata_id = :id and version = :version and step = :step order by sort")
    suspend fun getByMetadataIdAndVersionAndStep(id: UUID, version: Int, step: Long): List<GuideStepModule>

    @Query("select * from guide_step_modules where metadata_id = any(:ids)")
    suspend fun getByMetadataIds(ids: List<UUID>): List<GuideStepModule>

    @Query("select * from guide_step_modules where metadata_id = :id and version = :version and step = :step and id = :module")
    suspend fun getByMetadataIdAndVersionAndStepAndModule(id: UUID, version: Int, step: Long, module: Long): GuideStepModule

    @Query("update guide_step_modules set sort = :sort where id = :id")
    suspend fun setSort(id: Long, sort: Int)

    @Query("delete from guide_step_modules where id = :id")
    suspend fun delete(id: Long)
}