package bosca.content.collection.repository

import bosca.content.collection.model.CollectionTrait
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID


@Repository
interface CollectionTraitRepository {

    @Query("insert into collection_traits (collection_id, trait_id) values (:collectionId, :traitId)")
    suspend fun add(trait: CollectionTrait): CollectionTrait

    @Query("select * from collection_traits where collection_id = :id")
    suspend fun getByCollectionId(id: UUID): List<CollectionTrait>

    @Query("delete from collection_traits where collection_id = :id")
    suspend fun deleteByCollectionId(id: UUID)
}