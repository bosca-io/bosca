package bosca.content.collection.repository

import bosca.content.collection.model.CollectionSupplementary
import bosca.content.metadata.model.MetadataSupplementary
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface CollectionSupplementaryRepository {

    @Query("insert into collection_supplementary (collection_id, key, name, plan_id, attributes, created, modified, uploaded, content_type, content_length, source_id, source_identifier) values (:collectionId, :key, :name, :planId, :attributes, :created, :modified, :uploaded, :contentType, :contentLength, :sourceId, :sourceIdentifier) returning *")
    suspend fun add(supplementary: CollectionSupplementary): CollectionSupplementary

    @Query("update collection_supplementary set name = :name, plan_id = :planId, attributes = :attributes, created = :created, uploaded = :uploaded, content_type = :contentType, content_length = :contentLength, source_id = :sourceId, source_identifier = :sourceIdentifier, modified = now() where id = :id returning *")
    suspend fun update(supplementary: CollectionSupplementary): CollectionSupplementary

    @Query("select * from collection_supplementary where id = :id")
    suspend fun getById(id: UUID): CollectionSupplementary?

    @Query("select * from collection_supplementary where collection_id = :id order by modified desc")
    suspend fun getByCollectionId(id: UUID): List<CollectionSupplementary>

    @Query("update collection_supplementary set uploaded = now(), content_type = :contentType, content_length = :contentLength, modified = now() where id = :id")
    suspend fun setUploaded(id: UUID, contentType: String, contentLength: Long)

    @Query("delete from collection_supplementary where collection_id = :id")
    suspend fun deleteById(id: UUID)
}
