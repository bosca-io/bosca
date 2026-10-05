package bosca.content.metadata.repository

import bosca.content.metadata.model.MetadataSupplementary
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface MetadataSupplementaryRepository {

    @Query("insert into metadata_supplementary (metadata_id, key, name, plan_id, attributes, created, modified, uploaded, content_type, content_length, source_id, source_identifier) values (:metadataId, :key, :name, :planId, :attributes, :created, :modified, :uploaded, :contentType, :contentLength, :sourceId, :sourceIdentifier) returning *")
    suspend fun add(supplementary: MetadataSupplementary): MetadataSupplementary

    @Query("update metadata_supplementary set name = :name, plan_id = :planId, attributes = :attributes, created = :created, uploaded = :uploaded, content_type = :contentType, content_length = :contentLength, source_id = :sourceId, source_identifier = :sourceIdentifier, modified = now() where id = :id returning *")
    suspend fun update(supplementary: MetadataSupplementary): MetadataSupplementary

    @Query("select * from metadata_supplementary where id = :id")
    suspend fun getById(id: UUID): MetadataSupplementary?

    @Query("select * from metadata_supplementary where metadata_id = :id order by modified desc")
    suspend fun getByMetadataId(id: UUID): List<MetadataSupplementary>

    @Query("select * from metadata_supplementary where metadata_id = any(:ids) order by modified desc")
    suspend fun getByMetadataIds(ids: List<UUID>): List<MetadataSupplementary>

    @Query("select * from metadata_supplementary where metadata_id = :id and key = :key order by modified desc limit 1")
    suspend fun getByMetadataIdAndKey(id: UUID, key: String): MetadataSupplementary?

    @Query("update metadata_supplementary set uploaded = now(), modified = now(), content_type = :contentType, content_length = :contentLength where id = :id returning *")
    suspend fun setUploaded(id: UUID, contentType: String, contentLength: Long): MetadataSupplementary

    @Query("update metadata_supplementary set plan_id = null where id = :id")
    suspend fun detach(id: UUID)

    @Query("delete from metadata_supplementary where id = :id")
    suspend fun deleteById(id: UUID)
}