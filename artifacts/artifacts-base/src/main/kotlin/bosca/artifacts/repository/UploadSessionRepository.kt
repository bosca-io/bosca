package bosca.artifacts.repository

import bosca.artifacts.model.UploadSession
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

/**
 * Database operations for Docker chunked upload sessions.
 */
@Repository
interface UploadSessionRepository {

    @Query("SELECT * FROM artifacts.upload_sessions WHERE id = :id AND state = 'active' AND expires > now()")
    suspend fun findActive(id: UUID): UploadSession?

    @Query("SELECT * FROM artifacts.upload_sessions WHERE id = :id AND state = 'active'")
    suspend fun findActiveIncludingExpired(id: UUID): UploadSession?

    @Query("INSERT INTO artifacts.upload_sessions (repository_id) VALUES (:repositoryId) RETURNING *")
    suspend fun create(repositoryId: UUID): UploadSession

    @Query("UPDATE artifacts.upload_sessions SET storage_upload_id = :storageUploadId WHERE id = :id AND state = 'active' RETURNING *")
    suspend fun initializeStorageUpload(id: UUID, storageUploadId: String): UploadSession?

    @Query("UPDATE artifacts.upload_sessions SET byte_offset = :byteOffset, chunk_count = chunk_count + 1, digest_state = :digestState, expires = now() + INTERVAL '1 hour' WHERE id = :id AND state = 'active' RETURNING *")
    suspend fun updateOffset(id: UUID, byteOffset: Long, digestState: ByteArray): UploadSession?

    @Query("UPDATE artifacts.upload_sessions SET state = 'completed' WHERE id = :id RETURNING *")
    suspend fun complete(id: UUID): UploadSession?

    @Query("UPDATE artifacts.upload_sessions SET state = 'cancelled' WHERE id = :id RETURNING *")
    suspend fun cancel(id: UUID): UploadSession?

    @Query("SELECT * FROM artifacts.upload_sessions WHERE state = 'active' AND expires < now()")
    suspend fun findExpired(): List<UploadSession>
}
