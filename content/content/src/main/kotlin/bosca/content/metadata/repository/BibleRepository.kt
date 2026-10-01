package bosca.content.metadata.repository

import bosca.content.metadata.model.Bible
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface BibleRepository {

    @Query("select * from bibles where enabled")
    suspend fun getAll(): List<Bible>

    @Query("select * from bibles where metadata_id = :id and version = :version order by default_variant desc, name, variant")
    suspend fun getVariants(id: UUID, version: Int): List<Bible>

    @Query("select * from bibles where metadata_id = :id and version = :version and default_variant = :defaultVariant and enabled")
    suspend fun getById(id: UUID, version: Int, defaultVariant: Boolean): Bible?

    @Query("select * from bibles where metadata_id = :id and version = :version and variant = :variant and enabled")
    suspend fun getByIdAndVariant(id: UUID, version: Int, variant: String): Bible?

    @Query("select * from bibles where metadata_id = :id and version = :version and variant = :variant")
    suspend fun getByIdAndVariantIncludingDisabled(id: UUID, version: Int, variant: String): Bible?

    @Query("insert into bibles (metadata_id, version, system_id, variant, default_variant, enabled, name, name_local, description, abbreviation, abbreviation_local, styles) values (:metadataId, :version, :systemId, :variant, :defaultVariant, :enabled, :name, :nameLocal, :description, :abbreviation, :abbreviationLocal, :styles) returning *")
    suspend fun add(bible: Bible): Bible

    @Query("update bibles set enabled = :enabled where metadata_id = :id and version = :version and variant = :variant returning *")
    suspend fun setEnabled(id: UUID, version: Int, variant: String, enabled: Boolean): Bible?

    @Query("update bibles set default_variant = false where metadata_id = :id and version = :version and default_variant")
    suspend fun clearDefault(id: UUID, version: Int)

    @Query("update bibles set default_variant = true where metadata_id = :id and version = :version and variant = :variant and enabled returning *")
    suspend fun setDefault(id: UUID, version: Int, variant: String): Bible?

    @Query("delete from bibles where metadata_id = :id and version = :version")
    suspend fun deleteById(id: UUID, version: Int)

    @Query("delete from bibles where metadata_id = :id and version = :version and variant = :variant")
    suspend fun deleteById(id: UUID, version: Int, variant: String)
}
