package bosca.content.metadata.repository

import bosca.content.metadata.model.DataCollaboration
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface DataCollaborationRepository {

    @Query("select * from data_collaborations where metadata_id = :metadataId and version = :version")
    suspend fun getByMetadataIdAndVersion(metadataId: UUID, version: Int): DataCollaboration?

    @Query("select * from data_collaborations where metadata_id = :metadataId and version = :version for update")
    suspend fun getByMetadataIdAndVersionForUpdate(metadataId: UUID, version: Int): DataCollaboration?

    @Query("insert into data_collaborations (metadata_id, version, content) values (:metadataId, :version, :content) on conflict (metadata_id, version) do update set content = :content, modified = now()")
    suspend fun setCollaboration(collaboration: DataCollaboration)

    @Query("delete from data_collaborations where metadata_id = :metadataId and version = :version")
    suspend fun removeCollaboration(metadataId: UUID, version: Int)
}
