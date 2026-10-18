package bosca.backup

import bosca.db.annotation.Query
import bosca.db.annotation.Repository

/** Persists immutable revision intent and optimistic per-attempt progress in the backup schema. */
@Repository
interface ObjectBackupProgressRepository {
    /** Reserves immutable retained bytes within one destination, independently of logical source scope. */
    @Query(
        """
        insert into backup.object_paths (destination_id, path, length, sha256)
        values (:destinationId, :path, :length, :sha256)
        on conflict (destination_id, path) do nothing
        """,
        returnUpdateCount = true,
    )
    suspend fun addPayload(destinationId: String, path: String, length: Long, sha256: String): Int

    /** Checks whether an existing retained path identifies exactly these bytes. */
    @Query(
        """
        select exists (
            select 1 from backup.object_paths
            where destination_id = :destinationId and path = :path
                and length = :length and sha256 = :sha256
        )
        """,
    )
    suspend fun hasPayload(destinationId: String, path: String, length: Long, sha256: String): Boolean

    /** Reserves a manifest path before its owning run is inserted in the same transaction. */
    @Query(
        """
        insert into backup.object_paths (destination_id, path, manifest_key)
        values (:destinationId, :path, :runKey)
        on conflict (destination_id, path) do nothing
        """,
        returnUpdateCount = true,
    )
    suspend fun addManifest(destinationId: String, path: String, runKey: String): Int

    /** Checks the run ownership of an existing reserved manifest path. */
    @Query(
        """
        select exists (
            select 1 from backup.object_paths
            where destination_id = :destinationId and path = :path and manifest_key = :runKey
        )
        """,
    )
    suspend fun hasManifest(destinationId: String, path: String, runKey: String): Boolean

    /** Inserts an intent once; duplicate preparation leaves the existing record unchanged. */
    @Query(
        """
        insert into backup.object_progress (key, source_id, destination_id, scope, operation, payload)
        values (:key, :sourceId, :destinationId, :scope, :operation, :payload)
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
            and operation = :operation
        order by created, key limit :limit offset :offset
        """
    )
    suspend fun list(
        operation: ObjectBackupOperation,
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
            verified_bytes = 0, verified_version = null, verified = null,
            version = version + 1, modified = now()
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
            verified_version = null, verified = null,
            error = null, version = version + 1, modified = now()
        where key = :key and version = :expectedVersion and status = 'running'
        returning *
        """
    )
    suspend fun complete(key: String, expectedVersion: Long): ObjectBackupProgress?

    /** Completes a verified attempt with the exact provider version read by its caller. */
    @Query(
        """
        update backup.object_progress
        set status = 'completed', verified_bytes = (payload->>'length')::bigint,
            verified_version = :objectVersion,
            verified = case when cast(:objectVersion as text) is null then null else now() end,
            error = null, version = version + 1, modified = now()
        where key = :key and version = :expectedVersion and status = 'running'
        returning *
        """,
    )
    suspend fun completeVerified(key: String, expectedVersion: Long, objectVersion: String?): ObjectBackupProgress?

    /** Records a full checksum recheck without extending the time on metadata-only reads. */
    @Query(
        """
        update backup.object_progress
        set verified_version = :objectVersion,
            verified = case when cast(:objectVersion as text) is null then null else now() end,
            version = version + 1, modified = now()
        where key = :key and version = :expectedVersion and status = 'completed'
        returning *
        """,
    )
    suspend fun recordVerification(key: String, expectedVersion: Long, objectVersion: String?): ObjectBackupProgress?

    /** Fails the current attempt without accepting a stale worker's acknowledgement. */
    @Query(
        """
        update backup.object_progress
        set status = 'failed', error = :error, verified_bytes = 0,
            verified_version = null, verified = null, version = version + 1, modified = now()
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
            verified_version = null, verified = null, version = version + 1, modified = now()
        where key = :key and version = :expectedVersion and status = 'completed'
        returning *
        """
    )
    suspend fun invalidate(key: String, expectedVersion: Long, error: String): ObjectBackupProgress?
}
