package bosca.content.metadata.repository

import bosca.content.collection.model.CollectionTemplate
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@Repository
interface CollectionTemplateRepository {

    @Query("select * from collection_templates")
    suspend fun getAll(): List<CollectionTemplate>

    @Query("select * from collection_templates where metadata_id = :metadataId")
    suspend fun getByMetadataId(metadataId: UUID): CollectionTemplate?

    @Query("select * from collection_templates where metadata_id = :metadataId and version = :version")
    suspend fun getByMetadataIdAndVersion(metadataId: UUID, version: Int): CollectionTemplate?

    @Query("select * from collection_templates where metadata_id = any(:ids)")
    suspend fun getByMetadataIds(ids: List<UUID>): List<CollectionTemplate>

    @Query("insert into collection_templates (metadata_id, version, default_attributes, configuration, filters, ordering) values (:metadataId, :version, :defaultAttributes, :configuration, :filters, :ordering) on conflict (metadata_id, version) do update set default_attributes = :defaultAttributes, configuration = :configuration, filters = :filters, ordering = :ordering")
    suspend fun add(metadataId: UUID, version: Int, defaultAttributes: JsonElement?, configuration: JsonElement?, filters: JsonElement?, ordering: JsonElement?)

    @Query("delete from collection_templates where metadata_id = :metadataId and version = :version")
    suspend fun deleteTemplate(metadataId: UUID, version: Int)

    @Query("update collection_templates set default_attributes = :attributes where metadata_id = :metadataId and version = :version")
    suspend fun setDefaultAttributes(metadataId: UUID, version: Int, attributes: JsonElement?)

    @Query("update collection_templates set configuration = :configuration where metadata_id = :metadataId and version = :version")
    suspend fun setConfiguration(metadataId: UUID, version: Int, configuration: JsonElement?)

    @Query("update collection_templates set filters = :filters where metadata_id = :metadataId and version = :version")
    suspend fun setFilters(metadataId: UUID, version: Int, filters: JsonElement?)

    @Query("update collection_templates set ordering = :ordering where metadata_id = :metadataId and version = :version")
    suspend fun setOrdering(metadataId: UUID, version: Int, ordering: JsonElement?)
}