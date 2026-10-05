package bosca.backup

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

/**
 * Manages persistent records that track the lifecycle of backup operations.
 * Each backup has a status that progresses through pending, running,
 * completed, or failed, along with optional storage path and error details.
 */
@Repository
interface BackupRepository {

    /** Creates a new backup tracking record in the pending state. */
    @Query("insert into backups (id, include_files) values (:id, :includeFiles) returning *")
    suspend fun create(id: UUID, includeFiles: Boolean): BackupRecord

    /** Retrieves a backup record by its unique identifier. */
    @Query("select * from backups where id = :id")
    suspend fun getById(id: UUID): BackupRecord?

    /** Lists backup records ordered by creation date descending with pagination. */
    @Query("select * from backups order by created desc limit :limit offset :offset")
    suspend fun getAll(offset: Long, limit: Int): List<BackupRecord>

    /** Updates the status of a backup and refreshes the modified timestamp. */
    @Query("update backups set status = :status, modified = now() where id = :id")
    suspend fun updateStatus(id: UUID, status: String)

    /** Stores the object storage path of the completed backup archive. */
    @Query("update backups set path = :path, modified = now() where id = :id")
    suspend fun setPath(id: UUID, path: String)

    /** Links the backup record to its metadata item for content download. */
    @Query("update backups set metadata_id = :metadataId, modified = now() where id = :id")
    suspend fun setMetadataId(id: UUID, metadataId: UUID)

    /** Records an error message when the backup fails. */
    @Query("update backups set error = :error, status = 'failed', modified = now() where id = :id")
    suspend fun setError(id: UUID, error: String)

    /** Deletes a backup tracking record. */
    @Query("delete from backups where id = :id")
    suspend fun delete(id: UUID)
}
