package bosca.content.metadata.repository

import bosca.content.metadata.model.MetadataTrait
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface MetadataTraitRepository {

    @Query("insert into metadata_traits (metadata_id, trait_id) values (:metadataId, :traitId)")
    suspend fun addTrait(metadataId: UUID, traitId: String)

    @Query("delete from metadata_traits where metadata_id = :metadataId and trait_id = :traitId")
    suspend fun removeTrait(metadataId: UUID, traitId: String)

    @Query("select * from metadata_traits where metadata_id = :id")
    suspend fun getMetadataTraitsByMetadataId(id: UUID): List<MetadataTrait>

    @Query("select * from metadata_traits where metadata_id = any(:ids)")
    suspend fun getMetadataTraitsByMetadataIds(ids: List<UUID>): List<MetadataTrait>

    @Query("delete from metadata_traits where metadata_id = :id")
    suspend fun deleteByMetadataId(id: UUID)
}