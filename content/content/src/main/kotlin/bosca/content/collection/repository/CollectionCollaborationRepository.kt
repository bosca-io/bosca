package bosca.content.collection.repository

import bosca.content.collection.model.CollectionCollaboration
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface CollectionCollaborationRepository {

    @Query("select * from collection_collaborations where collection_id = :collectionId and language_tag = :languageTag")
    suspend fun getByCollectionId(collectionId: UUID, languageTag: String): CollectionCollaboration?

    @Query("select * from collection_collaborations where collection_id = :collectionId and language_tag = :languageTag for update")
    suspend fun getByCollectionIdForUpdate(collectionId: UUID, languageTag: String): CollectionCollaboration?

    @Query("insert into collection_collaborations (collection_id, language_tag, content) values (:collectionId, :languageTag, :content) on conflict (collection_id, language_tag) do update set content = :content, modified = now()")
    suspend fun setCollaboration(collaboration: CollectionCollaboration)
}