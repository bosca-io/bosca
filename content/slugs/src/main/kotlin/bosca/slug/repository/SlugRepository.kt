package bosca.slug.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.slug.model.Slug

@Repository
interface SlugRepository {

    @Query("select * from slugs where slug = :slug")
    suspend fun get(slug: String): Slug?

    @Query("insert into slugs (profile_id, collection_id, language_tag, metadata_id, slug) values (:profileId, :collectionId, :languageTag, :metadataId, :slug) returning *")
    suspend fun add(slug: Slug): Slug

    @Query("select * from slugs where profile_id = :id")
    suspend fun getSlugByProfileId(id: UUID): Slug?

    @Query("delete from slugs where profile_id = :id")
    suspend fun deleteSlugByProfileId(id: UUID)

    @Query("select * from slugs where collection_id = :id and language_tag is null")
    suspend fun getSlugByCollectionId(id: UUID): Slug?

    @Query("select * from slugs where collection_id = :id and language_tag = :languageTag")
    suspend fun getSlugByCollectionId(id: UUID, languageTag: String): Slug?

    @Query("delete from slugs where collection_id = :id and language_tag is null")
    suspend fun deleteSlugByCollectionId(id: UUID)

    @Query("delete from slugs where collection_id = :id and language_tag = :languageTag")
    suspend fun deleteSlugByCollectionId(id: UUID, languageTag: String)

    @Query("select * from slugs where metadata_id = :id")
    suspend fun getSlugByMetadataId(id: UUID): Slug?

    @Query("select * from slugs where metadata_id = any(:ids)")
    suspend fun getSlugByMetadataIds(ids: List<UUID>): List<Slug>

    @Query("select * from slugs where collection_id = any(:ids)")
    suspend fun getSlugByCollectionIds(ids: List<UUID>): List<Slug>

    @Query("select * from slugs where profile_id = any(:ids)")
    suspend fun getSlugByProfileId(ids: List<UUID>): List<Slug>

    @Query("delete from slugs where metadata_id = :id")
    suspend fun deleteSlugByMetadataId(id: UUID)

    @Query("delete from slugs where slug = :slug")
    suspend fun deleteById(slug: String)
}