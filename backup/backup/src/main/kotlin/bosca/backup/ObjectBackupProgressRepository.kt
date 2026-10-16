package bosca.backup

import bosca.db.annotation.Query
import bosca.db.annotation.Repository

/** Persists immutable revision intent and optimistic per-attempt progress in the backup schema. */
@Repository
interface ObjectBackupProgressRepository {
    /** Inserts an intent once; duplicate preparation leaves the existing record unchanged. */
    @Query(
        """
        insert into backup.object_progress (key, source_id, destination_id, scope, payload)
        values (:key, :sourceId, :destinationId, :scope, :payload)
        on conflict (key) do nothing
        """,
        returnUpdateCount = true,
    )
    suspend fun add(progress: ObjectBackupProgress): Int

    /** Reads the last persisted state for one revision transfer. */
    @Query("select * from backup.object_progress where key = :key")
    suspend fun get(key: String): ObjectBackupProgress?

    /** Lists progress in a declared source/destination scope using stable offset pagination. */
    @Query(
        """
        select * from backup.object_progress
        where source_id = :sourceId and destination_id = :destinationId and scope = :scope
        order by created, key limit :limit offset :offset
        """
    )
    suspend fun list(
        sourceId: String,
        destinationId: String,
        scope: String,
        offset: Long,
        limit: Int,
    ): List<ObjectBackupProgress>

    /** Starts one pending or failed attempt; a stale version or active/completed state returns null. */
    @Query(
        """
        update backup.object_progress
        set status = 'running', attempts = attempts + 1, error = null,
            verified_bytes = 0, version = version + 1, modified = now()
        where key = :key and version = :expectedVersion and status in ('pending', 'failed')
        returning *
        """
    )
    suspend fun start(key: String, expectedVersion: Long): ObjectBackupProgress?

    /** Records a verified completed payload for the current running attempt only. */
    @Query(
        """
        update backup.object_progress
        set status = 'completed', verified_bytes = (payload->>'length')::bigint,
            error = null, version = version + 1, modified = now()
        where key = :key and version = :expectedVersion and status = 'running'
        returning *
        """
    )
    suspend fun complete(key: String, expectedVersion: Long): ObjectBackupProgress?

    /** Fails the current attempt without accepting a stale worker's acknowledgement. */
    @Query(
        """
        update backup.object_progress
        set status = 'failed', error = :error, verified_bytes = 0,
            version = version + 1, modified = now()
        where key = :key and version = :expectedVersion and status = 'running'
        returning *
        """
    )
    suspend fun fail(key: String, expectedVersion: Long, error: String): ObjectBackupProgress?

    /** Invalidates completed progress after the caller establishes that its output was lost or corrupt. */
    @Query(
        """
        update backup.object_progress
        set status = 'failed', error = :error, verified_bytes = 0,
            version = version + 1, modified = now()
        where key = :key and version = :expectedVersion and status = 'completed'
        returning *
        """
    )
    suspend fun invalidate(key: String, expectedVersion: Long, error: String): ObjectBackupProgress?
}
