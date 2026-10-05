package bosca.content.collection.repository

import bosca.content.collection.model.CollectionCategory
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface CollectionCategoryRepository {

    @Query("insert into collection_categories (collection_id, category_id) values (:collectionId, :categoryId) returning *")
    suspend fun add(category: CollectionCategory): CollectionCategory

    @Query("select * from collection_categories where collection_id = :id")
    suspend fun getByCollectionId(id: UUID): List<CollectionCategory>

    @Query("delete from collection_categories where collection_id = :collectionId")
    suspend fun deleteByCollectionId(collectionId: UUID)
}