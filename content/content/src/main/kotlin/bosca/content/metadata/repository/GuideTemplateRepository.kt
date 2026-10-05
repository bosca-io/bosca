package bosca.content.metadata.repository

import bosca.content.metadata.model.GuideTemplate
import bosca.content.metadata.model.GuideType
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@Repository
interface GuideTemplateRepository {

    @Query("select * from guide_templates inner join metadata on guide_templates.metadata_id = metadata.id and guide_templates.version = metadata.version where deleted = false order by metadata.name desc")
    suspend fun getAll(): List<GuideTemplate>

    @Query("select * from guide_templates where metadata_id = :id and version = :version")
    suspend fun getByMetadataIdAndVersion(id: UUID, version: Int): GuideTemplate?

    @Query("select * from guide_templates where metadata_id = any(:ids)")
    suspend fun getByMetadataIdBatch(ids: List<UUID>): List<GuideTemplate>

    @Query("insert into guide_templates (metadata_id, version, default_attributes, configuration, rrule, type) values (:id, :version, :defaultAttributes, :configuration, :rrule, :type) on conflict (metadata_id, version) do update set default_attributes = :defaultAttributes, configuration = :configuration, rrule = :rrule, type = :type")
    suspend fun add(id: UUID, version: Int, defaultAttributes: JsonElement?, configuration: JsonElement?, rrule: String?, type: GuideType)

    @Query("update guide_templates set default_attributes = :attributes where metadata_id = :id and version = :version")
    suspend fun setDefaultAttributes(id: UUID, version: Int, attributes: JsonElement?)

    @Query("update guide_templates set configuration = :configuration where metadata_id = :id and version = :version")
    suspend fun setConfiguration(id: UUID, version: Int, configuration: JsonElement?)

    @Query("update guide_templates set rrule = :rrule where metadata_id = :id and version = :version")
    suspend fun setRrule(id: UUID, version: Int, rrule: String?)

    @Query("update guide_templates set type = :type where metadata_id = :id and version = :version")
    suspend fun setType(id: UUID, version: Int, type: GuideType)
}