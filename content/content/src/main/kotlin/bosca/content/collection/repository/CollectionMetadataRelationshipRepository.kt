package bosca.content.collection.repository

import bosca.content.collection.model.CollectionLanguageVariantMetadataRelationship
import bosca.content.collection.model.CollectionMetadataRelationship
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@Repository
interface CollectionMetadataRelationshipRepository {

    @Query("insert into collection_metadata_relationships (collection_id, metadata_id, relationship, attributes) values (:collectionId, :metadataId, :relationship, :attributes) returning *")
    suspend fun add(relationship: CollectionMetadataRelationship): CollectionMetadataRelationship

    @Query("insert into collection_variant_metadata_relationships (collection_id, language_tag, metadata_id, relationship, attributes) values (:collectionId, :languageTag, :metadataId, :relationship, :attributes) returning *")
    suspend fun addVariant(relationship: CollectionLanguageVariantMetadataRelationship): CollectionLanguageVariantMetadataRelationship

    @Query("select * from collection_metadata_relationships where collection_id = :id order by coalesce((attributes->>'sort')::int, 0) asc, metadata_id asc")
    suspend fun getByCollectionId(id: UUID): List<CollectionMetadataRelationship>

    @Query("select * from collection_variant_metadata_relationships where collection_id = :id and language_tag = :languageTag order by coalesce((attributes->>'sort')::int, 0) asc, metadata_id asc")
    suspend fun getVariantByCollectionIdAndLanguageTag(id: UUID, languageTag: String): List<CollectionLanguageVariantMetadataRelationship>

    @Query("select * from collection_variant_metadata_relationships where collection_id = :id order by language_tag, coalesce((attributes->>'sort')::int, 0) asc, metadata_id asc")
    suspend fun getVariantByCollectionId(id: UUID): List<CollectionLanguageVariantMetadataRelationship>

    @Query("select * from collection_metadata_relationships where collection_id = any(:ids) order by collection_id, coalesce((attributes->>'sort')::int, 0) asc, metadata_id asc")
    suspend fun getByCollectionIds(ids: List<UUID>): List<CollectionMetadataRelationship>

    @Query("select * from collection_variant_metadata_relationships where collection_id = any(:ids) order by collection_id, language_tag, coalesce((attributes->>'sort')::int, 0) asc, metadata_id asc")
    suspend fun getVariantByCollectionIds(ids: List<UUID>): List<CollectionLanguageVariantMetadataRelationship>

    @Query("update collection_metadata_relationships set attributes = :attributes where collection_id = :collectionId and metadata_id = :metadataId and relationship = :relationship returning *")
    suspend fun updateByCollectionIdAndMetadataIdAndRelationship(
        collectionId: UUID,
        metadataId: UUID,
        relationship: String,
        attributes: JsonElement?
    ): CollectionMetadataRelationship

    @Query("update collection_variant_metadata_relationships set attributes = :attributes where collection_id = :collectionId and language_tag = :languageTag and metadata_id = :metadataId and relationship = :relationship returning *")
    suspend fun updateVariantByCollectionIdAndLanguageTagAndMetadataIdAndRelationship(
        collectionId: UUID,
        languageTag: String,
        metadataId: UUID,
        relationship: String,
        attributes: JsonElement?
    ): CollectionLanguageVariantMetadataRelationship

    @Query("update collection_metadata_relationships set attributes = coalesce(attributes, '{}') || (:attributes)::jsonb where collection_id = :collectionId and metadata_id = :metadataId and relationship = :relationship returning *")
    suspend fun mergeAttributesByCollectionIdAndMetadataIdAndRelationship(
        collectionId: UUID,
        metadataId: UUID,
        relationship: String,
        attributes: JsonElement?
    ): CollectionMetadataRelationship

    @Query("update collection_variant_metadata_relationships set attributes = coalesce(attributes, '{}') || (:attributes)::jsonb where collection_id = :collectionId and language_tag = :languageTag and metadata_id = :metadataId and relationship = :relationship returning *")
    suspend fun mergeVariantAttributesByCollectionIdAndLanguageTagAndMetadataIdAndRelationship(
        collectionId: UUID,
        languageTag: String,
        metadataId: UUID,
        relationship: String,
        attributes: JsonElement?
    ): CollectionLanguageVariantMetadataRelationship

    @Query("delete from collection_metadata_relationships where collection_id = :collectionId and metadata_id = :metadataId and relationship = :relationship")
    suspend fun deleteByCollectionIdAndMetadataIdAndRelationship(
        collectionId: UUID,
        metadataId: UUID,
        relationship: String
    )

    @Query("delete from collection_variant_metadata_relationships where collection_id = :collectionId and language_tag = :languageTag and metadata_id = :metadataId and relationship = :relationship")
    suspend fun deleteVariantByCollectionIdAndLanguageTagAndMetadataIdAndRelationship(
        collectionId: UUID,
        languageTag: String,
        metadataId: UUID,
        relationship: String
    )
}