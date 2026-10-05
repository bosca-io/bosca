package bosca.content.metadata.repository

import bosca.content.metadata.model.ContentEntityLink
import bosca.content.metadata.model.ContentLinkTarget
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@Repository
interface ContentEntityLinkRepository {

    @Query(
        """
        insert into content_entity_link (
            metadata_id, metadata_version, target_type, target_id, node_type, position
        ) values (
            :metadataId, :metadataVersion, (:targetType)::content_link_target, :targetId, :nodeType, :position::jsonb
        )
        returning *
        """
    )
    suspend fun add(
        metadataId: UUID,
        metadataVersion: Int,
        targetType: ContentLinkTarget,
        targetId: String,
        nodeType: String?,
        position: JsonElement?,
    ): ContentEntityLink

    @Query(
        """
        select * from content_entity_link
        where metadata_id = :metadataId and metadata_version = :metadataVersion
        order by created_at
        """
    )
    suspend fun listBySource(metadataId: UUID, metadataVersion: Int): List<ContentEntityLink>

    @Query(
        """
        select * from content_entity_link
        where target_type = (:targetType)::content_link_target and target_id = :targetId
        order by created_at desc
        """
    )
    suspend fun listByTarget(targetType: ContentLinkTarget, targetId: String): List<ContentEntityLink>

    @Query(
        """
        select * from content_entity_link
        where metadata_id = :metadataId
        order by metadata_version desc, created_at
        """
    )
    suspend fun listByMetadata(metadataId: UUID): List<ContentEntityLink>

    @Query("delete from content_entity_link where metadata_id = :metadataId and metadata_version = :metadataVersion")
    suspend fun deleteBySource(metadataId: UUID, metadataVersion: Int)

    @Query("delete from content_entity_link where metadata_id = :metadataId")
    suspend fun deleteByMetadata(metadataId: UUID)
}
