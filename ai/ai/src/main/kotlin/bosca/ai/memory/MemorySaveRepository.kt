package bosca.ai.memory

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface MemorySaveRepository {
    /** Retains the first revision delivered to a native session until its context is discarded. */
    @Query("insert into ai.memory_reads (session_id, metadata_id, revision, source_changed) values (:sessionId, :metadataId, :revision, :sourceChanged) on conflict do nothing")
    suspend fun recordRead(sessionId: UUID, metadataId: UUID, revision: String, sourceChanged: Boolean)

    /** Lists delivered revisions in stable order for context and checkpoint validation. */
    @Query("select * from ai.memory_reads where session_id = :sessionId order by metadata_id")
    suspend fun reads(sessionId: UUID): List<MemoryRead>

    /** Clears receipts after the old native session has stopped. */
    @Query("delete from ai.memory_reads where session_id = :sessionId")
    suspend fun clearReads(sessionId: UUID)

    /** Claims a caller's idempotency key without replacing an existing save receipt. */
    @Query("insert into ai.memory_saves (principal_id, request_id, input_digest) values (:principalId, :requestId, :inputDigest) on conflict do nothing")
    suspend fun claim(principalId: UUID, requestId: UUID, inputDigest: String)

    /** Locks the receipt within the save transaction to serialize duplicate requests. */
    @Query("select * from ai.memory_saves where principal_id = :principalId and request_id = :requestId for update")
    suspend fun getForUpdate(principalId: UUID, requestId: UUID): MemorySave?

    /** Commits the created content identity in the same transaction as the memory document. */
    @Query("update ai.memory_saves set metadata_id = :metadataId where principal_id = :principalId and request_id = :requestId")
    suspend fun complete(principalId: UUID, requestId: UUID, metadataId: UUID)
}
