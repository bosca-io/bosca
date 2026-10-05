package bosca.content.metadata.repository

import bosca.content.metadata.model.MetadataCategory
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface MetadataCategoryRepository {

    @Query("insert into metadata_categories (metadata_id, category_id) values (:metadataId, :categoryId) returning *")
    suspend fun add(category: MetadataCategory): MetadataCategory

    @Query("select * from metadata_categories where metadata_id = :id")
    suspend fun getMetadataCategoryByMetadataId(id: UUID): List<MetadataCategory>

    @Query("select * from metadata_categories where metadata_id = any(:ids)")
    suspend fun getMetadataCategoryByMetadataIds(ids: List<UUID>): List<MetadataCategory>

    @Query("delete from metadata_categories where metadata_id = :id")
    suspend fun deleteByMetadataId(id: UUID)

    @Query("delete from metadata_categories where metadata_id = :id and category_id = :traitId")
    suspend fun deleteByMetadataId(id: UUID, traitId: UUID)
}