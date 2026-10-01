package bosca.content.metadata.repository

import bosca.content.metadata.model.Data
import bosca.content.metadata.model.DataType
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface DataRepository {

    @Query("insert into data (metadata_id, version, type, template_metadata_id, template_metadata_version) values (:metadataId, :version, :type::data_type, :templateMetadataId, :templateMetadataVersion) returning *")
    suspend fun add(data: Data): Data

    @Query("update data set type = :type::data_type, template_metadata_id = :templateMetadataId, template_metadata_version = :templateMetadataVersion where metadata_id = :metadataId and version = :version")
    suspend fun edit(data: Data)

    @Query("insert into data (metadata_id, version, template_metadata_id, template_metadata_version) values (:id, :version, :templateMetadataId, :templateMetadataVersion) on conflict (metadata_id, version) do update set template_metadata_id = :templateMetadataId, template_metadata_version = :templateMetadataVersion")
    suspend fun setTemplate(id: UUID, version: Int, templateMetadataId: UUID, templateMetadataVersion: Int)

    @Query("update data set type = :type where metadata_id = :id and version = :version")
    suspend fun setType(id: UUID, version: Int, type: DataType)

    @Query("select * from data where metadata_id = :id and version = :version")
    suspend fun getByMetadataIdAndVersion(id: UUID, version: Int): Data?

    @Query("select * from data where metadata_id = any(:ids)")
    suspend fun getByMetadataIds(ids: List<UUID>): List<Data>

    @Query("delete from data where metadata_id = :metadataId and version = :version")
    suspend fun delete(metadataId: UUID, version: Int)
}
