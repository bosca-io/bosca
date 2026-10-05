package bosca.content.collection.repository

import bosca.content.collection.model.CollectionItem
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@Repository
interface CollectionItemRepository {

    @Query("insert into collection_items (collection_id, child_collection_id, child_metadata_id, attributes) values (:collectionId, :childCollectionId, :childMetadataId, :attributes) returning *")
    suspend fun add(item: CollectionItem): CollectionItem

    @Query("delete from collection_items where collection_id = :collectionId and child_collection_id = :childCollectionId")
    suspend fun deleteByCollectionIdAndChildCollectionId(collectionId: UUID, childCollectionId: UUID)

    @Query("delete from collection_items where collection_id = :collectionId and child_metadata_id = :childMetadataId")
    suspend fun deleteByCollectionIdAndChildMetadataId(collectionId: UUID, childMetadataId: UUID)

    @Query("select * from collection_items where child_collection_id = :id offset :offset limit :limit")
    suspend fun getCollectionParents(id: UUID, offset: Long, limit: Int): List<CollectionItem>

    @Query("select * from collection_items where child_collection_id = :id")
    suspend fun getCollectionParents(id: UUID): List<CollectionItem>

    @Query("select * from collection_items where child_metadata_id = :id offset :offset limit :limit")
    suspend fun getMetadataParents(id: UUID, offset: Long, limit: Int): List<CollectionItem>

    @Query("select * from collection_items where child_metadata_id = :id")
    suspend fun getMetadataParents(id: UUID): List<CollectionItem>

    @Query("select collection_items.* from collection_items where collection_id = :id offset :offset limit :limit")
    suspend fun getItems(id: UUID, offset: Long, limit: Int): List<CollectionItem>

    @Query("select collection_items.* from collection_items left join collections on (collection_items.child_collection_id = collections.id and collections.workflow_state_id = :state) left join metadata on (collection_items.child_metadata_id = metadata.id and metadata.workflow_state_id = :state) where collection_id = :id and (collections.id is not null or metadata.id is not null) offset :offset limit :limit")
    suspend fun getItemsWithState(id: UUID, state: String, offset: Long, limit: Int): List<CollectionItem>

    @Query("select collection_items.* from collection_items left join collections on (collection_items.child_collection_id = collections.id and lower(collections.language_tag) = lower(:languageTag)) left join collection_language_variants on (collection_items.child_collection_id = collection_language_variants.id and lower(collection_language_variants.language_tag) = lower(:languageTag)) left join metadata on (collection_items.child_metadata_id = metadata.id and lower(metadata.language_tag) = lower(:languageTag)) where collection_id = :id and (collections.id is not null or metadata.id is not null or collection_language_variants.id is not null) offset :offset limit :limit")
    suspend fun getItemsWithLanguageTag(id: UUID, offset: Long, limit: Int, languageTag: String): List<CollectionItem>

    @Query("select collection_items.* from collection_items left join collections on (collection_items.child_collection_id = collections.id and collections.workflow_state_id = :state and lower(collections.language_tag) = lower(:languageTag)) left join collection_language_variants on (collection_items.child_collection_id = collection_language_variants.id and lower(collection_language_variants.language_tag) = lower(:languageTag)) left join metadata on (collection_items.child_metadata_id = metadata.id and metadata.workflow_state_id = :state and lower(metadata.language_tag) = lower(:languageTag)) where collection_id = :id and (collections.id is not null or metadata.id is not null or collection_language_variants.id is not null) offset :offset limit :limit")
    suspend fun getItemsWithStateAndLanguageTag(id: UUID, state: String, offset: Long, limit: Int, languageTag: String): List<CollectionItem>

    @Query("select count(*) from collection_items where collection_id = :id")
    suspend fun getItemsCount(id: UUID): Long

    @Query("select count(*) from collection_items left join collections on (collection_items.child_collection_id = collections.id and collections.workflow_state_id = :state) left join metadata on (collection_items.child_metadata_id = metadata.id and metadata.workflow_state_id = :state) where collection_id = :id and (collections.id is not null or metadata.id is not null)")
    suspend fun getItemsWithStateCount(id: UUID, state: String): Long

    @Query("select count(*) from collection_items left join collections on (collection_items.child_collection_id = collections.id and lower(collections.language_tag) = lower(:languageTag)) left join collection_language_variants on (collection_items.child_collection_id = collection_language_variants.id and lower(collection_language_variants.language_tag) = lower(:languageTag)) left join metadata on (collection_items.child_metadata_id = metadata.id and lower(metadata.language_tag) = lower(:languageTag)) where collection_id = :id and (collections.id is not null or metadata.id is not null or collection_language_variants.id is not null)")
    suspend fun getItemsWithLanguageTagCount(id: UUID, languageTag: String): Long

    @Query("select count(*) from collection_items left join collections on (collection_items.child_collection_id = collections.id and collections.workflow_state_id = :state and lower(collections.language_tag) = lower(:languageTag)) left join collection_language_variants on (collection_items.child_collection_id = collection_language_variants.id and lower(collection_language_variants.language_tag) = lower(:languageTag)) left join metadata on (collection_items.child_metadata_id = metadata.id and metadata.workflow_state_id = :state and lower(metadata.language_tag) = lower(:languageTag)) where collection_id = :id and (collections.id is not null or metadata.id is not null or collection_language_variants.id is not null)")
    suspend fun getItemsWithStateAndLanguageTagCount(id: UUID, state: String, languageTag: String): Long

    @Query("select collection_items.* from collection_items where collection_id = :id and child_collection_id is not null offset :offset limit :limit")
    suspend fun getCollections(id: UUID, offset: Long, limit: Int): List<CollectionItem>

    @Query("select collection_items.* from collection_items inner join collections on (collection_items.child_collection_id = collections.id and collections.workflow_state_id = :state) where collection_id = :id offset :offset limit :limit")
    suspend fun getCollections(id: UUID, state: String, offset: Long, limit: Int): List<CollectionItem>

    @Query("select * from collection_items where collection_id = :id and child_metadata_id = :metadataId")
    suspend fun getCollectionMetadataItem(id: UUID, metadataId: UUID): CollectionItem?

    @Query("select * from collection_items where collection_id = :id and child_collection_id = :collectionId")
    suspend fun getCollectionCollectionItem(id: UUID, collectionId: UUID): CollectionItem?

    @Query("select count(*) from collection_items where collection_id = :id and child_collection_id is not null")
    suspend fun getCollectionsCount(id: UUID): Long

    @Query("select count(*) from collection_items inner join collections on (collection_items.child_collection_id = collections.id and collections.workflow_state_id = :state) where collection_id = :id")
    suspend fun getCollectionsCount(id: UUID, state: String): Long

    @Query("select collection_items.* from collection_items where collection_id = :id and child_metadata_id is not null offset :offset limit :limit")
    suspend fun getMetadata(id: UUID, offset: Long, limit: Int): List<CollectionItem>

    @Query("select collection_items.* from collection_items where collection_id = :id and child_metadata_id is not null offset :offset limit :limit")
    suspend fun getMetadata(id: UUID, state: String, offset: Long, limit: Int): List<CollectionItem>

    @Query("select count(*) from collection_items where collection_id = :id and child_metadata_id is not null")
    suspend fun getMetadataCount(id: UUID): Long

    @Query("select count(*) from collection_items inner join metadata on (metadata.id = collection_items.child_metadata_id and metadata.workflow_state_id = :state) where collection_id = :id")
    suspend fun getMetadataCount(id: UUID, state: String): Long

    @Query("update collection_items set attributes = :attributes where collection_id = :collectionId and child_metadata_id = :metadataItemId")
    suspend fun setMetadataAttributes(collectionId: UUID, metadataItemId: UUID, attributes: JsonElement?)

    @Query("update collection_items set attributes = :attributes where collection_id = :collectionId and child_collection_id = :collectionItemId")
    suspend fun setCollectionAttributes(collectionId: UUID, collectionItemId: UUID, attributes: JsonElement?)

    @Query("update collection_items set attributes = coalesce(attributes, '{}'::jsonb) || :attributes where collection_id = :collectionId and child_metadata_id = :metadataItemId")
    suspend fun mergeMetadataAttributes(collectionId: UUID, metadataItemId: UUID, attributes: JsonElement?)

    @Query("update collection_items set attributes = coalesce(attributes, '{}'::jsonb) || :attributes where collection_id = :collectionId and child_collection_id = :collectionItemId")
    suspend fun mergeCollectionAttributes(collectionId: UUID, collectionItemId: UUID, attributes: JsonElement?)

    @Query("delete from collection_items where collection_id = :collectionId and child_metadata_id = :childMetadataId")
    suspend fun removeMetadataItem(collectionId: UUID, childMetadataId: UUID)

    @Query("delete from collection_items where collection_id = :collectionId and child_collection_id = :childCollectionId")
    suspend fun removeCollectionItem(collectionId: UUID, childCollectionId: UUID)

}