package bosca.content.metadata.repository

import bosca.content.collection.model.CollectionTemplateAttribute
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface CollectionTemplateAttributeRepository {

    @Query("insert into collection_template_attributes (metadata_id, version, key, name, description, supplementary_key, configuration, type, ui, list, sort, location, tools) values (:metadataId, :version, :key, :name, :description, :supplementaryKey, :configuration, :type, :ui, :list, :sort, :location, :tools) returning *")
    suspend fun add(attribute: CollectionTemplateAttribute): CollectionTemplateAttribute

    @Query("select * from collection_template_attributes where metadata_id = any(:metadataId) order by sort")
    suspend fun getByMetadataIds(metadataId: List<UUID>): List<CollectionTemplateAttribute>

    @Query("select * from collection_template_attributes where metadata_id = :metadataId and version = :version order by sort")
    suspend fun getByMetadataIdAndVersion(metadataId: UUID, version: Int): List<CollectionTemplateAttribute>

    @Query("delete from collection_template_attributes where metadata_id = :metadataId and version = :version and key = :key")
    suspend fun deleteAttribute(metadataId: UUID, version: Int, key: String)

    @Query("delete from collection_template_attributes where metadata_id = :metadataId and version = :version")
    suspend fun deleteAttributes(metadataId: UUID, version: Int)

    @Query("delete from collection_template_attributes where metadata_id = :metadataId and version = :version")
    suspend fun deleteByMetadataIdAndVersion(metadataId: UUID, version: Int)
}