package bosca.content.metadata.repository

import bosca.content.metadata.model.MetadataRelationship
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@Repository
interface MetadataRelationshipRepository {

    @Query("insert into metadata_relationships (metadata1_id, metadata2_id, relationship, attributes) values (:metadataId1, :metadataId2, :relationship, :attributes) returning *")
    suspend fun add(relationship: MetadataRelationship): MetadataRelationship

    @Query("select * from metadata_relationships where metadata1_id = :id order by coalesce((attributes->>'sort')::int, 0) asc, metadata2_id asc")
    suspend fun getByMetadataId1(id: UUID): List<MetadataRelationship>

    @Query("select * from metadata_relationships where metadata1_id = any(:ids) order by coalesce((attributes->>'sort')::int, 0) asc, metadata2_id asc")
    suspend fun getByMetadataId1Batch(ids: List<UUID>): List<MetadataRelationship>

    @Query("delete from metadata_relationships where metadata1_id = :id1 and metadata2_id = :id2 and relationship = :relationship")
    suspend fun removeByMetadataId1AndMetadataId2AndRelationship(id1: UUID, id2: UUID, relationship: String)

    @Query("update metadata_relationships set attributes = :attributes || (case when jsonb_typeof(attributes) = 'null' then '{}'::jsonb else attributes end) where metadata1_id = :id1 and metadata2_id = :id2 and relationship = :relationship")
    suspend fun mergeAttributeRelationships(id1: UUID, id2: UUID, relationship: String, attributes: JsonElement)

    @Query("update metadata_relationships set attributes = :attributes where metadata1_id = :id1 and metadata2_id = :id2 and relationship = :relationship")
    suspend fun setAttributeRelationships(id1: UUID, id2: UUID, relationship: String, attributes: JsonElement)

    @Query("select attributes from metadata_relationships where metadata1_id = :id1 and metadata2_id = :id2 and relationship = :relationship")
    suspend fun getAttributes(id1: UUID, id2: UUID, relationship: String): JsonElement?
}