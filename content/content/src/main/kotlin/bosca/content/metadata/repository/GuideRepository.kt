package bosca.content.metadata.repository

import bosca.content.metadata.model.Guide
import bosca.content.metadata.model.GuideType
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface GuideRepository {

    @Query("insert into guides (metadata_id, version, rrule, type, template_metadata_id, template_metadata_version) values (:metadataId, :version, :rrule, :type, :templateMetadataId, :templateMetadataVersion) returning *")
    suspend fun add(guide: Guide): Guide

    @Query("select * from guides where metadata_id = :id and version = :version")
    suspend fun getByMetadataIdAndVersion(id: UUID, version: Int): Guide?

    @Query("select * from guides where metadata_id = any(:ids)")
    suspend fun getByMetadataIds(ids: List<UUID>): List<Guide>

    @Query("delete from guides where metadata_id = :id and version = :version")
    suspend fun deleteByMetadataIdAndVersion(id: UUID, version: Int)

    @Query("update guides set rrule = :rrule where metadata_id = :id and version = :version")
    suspend fun setRrule(id: UUID, version: Int, rrule: String?)

    @Query("update guides set type = :type where metadata_id = :id and version = :version")
    suspend fun setType(id: UUID, version: Int, type: GuideType)
}