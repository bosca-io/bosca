package bosca.content.metadata.repository

import bosca.content.metadata.model.DataTemplate
import bosca.content.metadata.model.DataType
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@Repository
interface DataTemplateRepository {

    @Query("select * from data_templates inner join metadata on metadata_id = metadata.id where metadata.content_type = 'bosca/v-data-template'")
    suspend fun getAll(): List<DataTemplate>

    @Query("insert into data_templates (metadata_id, version, type, default_attributes) values (:metadataId, :version, :type::data_type, :defaultAttributes) returning *")
    suspend fun add(template: DataTemplate)

    @Query("update data_templates set type = :type::data_type, default_attributes = :defaultAttributes where metadata_id = :metadataId and version = :version")
    suspend fun edit(template: DataTemplate)

    @Query("update data_templates set type = :type where metadata_id = :id and version = :version")
    suspend fun setType(id: UUID, version: Int, type: DataType)

    @Query("select * from data_templates where metadata_id = :id and version = :version")
    suspend fun getByMetadataIdAndVersion(id: UUID, version: Int): DataTemplate?

    @Query("select * from data_templates where metadata_id = any(:ids)")
    suspend fun getByMetadataIds(ids: List<UUID>): List<DataTemplate>

    @Query("delete from data_templates where metadata_id = :metadataId and version = :version")
    suspend fun deleteTemplate(metadataId: UUID, version: Int)

    @Query("update data_templates set default_attributes = :attributes where metadata_id = :metadataId and version = :version")
    suspend fun setDefaultAttributes(metadataId: UUID, version: Int, attributes: JsonElement?)
}