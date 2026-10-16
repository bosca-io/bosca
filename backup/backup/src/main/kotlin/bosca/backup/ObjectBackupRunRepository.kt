package bosca.backup

import bosca.db.annotation.Query
import bosca.db.annotation.Repository

/** Persists inventory runs and their exact associations to shared revision checkpoints. */
@Repository
interface ObjectBackupRunRepository {
    /** Inserts a run once, without resetting an existing attempt or result. */
    @Query(
        """
        insert into backup.object_runs (key, source_id, destination_id, scope, operation, inventory, path)
        values (:key, :sourceId, :destinationId, :scope, :operation, :inventory, :path)
        on conflict (key) do nothing
        """,
        returnUpdateCount = true,
    )
    suspend fun add(run: ObjectBackupRun): Int

    /** Associates one declared inventory position with its immutable revision intent. */
    @Query(
        """
        insert into backup.object_run_items (run_key, position, progress_key)
        values (:runKey, :position, :progressKey)
        """,
        returnUpdateCount = true,
    )
    suspend fun addItem(runKey: String, position: Int, progressKey: String): Int

    /** Reads persisted run intent and its latest terminal result. */
    @Query("select * from backup.object_runs where key = :key")
    suspend fun get(key: String): ObjectBackupRun?

    /** Lists run history within one source/destination scope, newest first. */
    @Query(
        """
        select * from backup.object_runs
        where source_id = :sourceId and destination_id = :destinationId and scope = :scope
            and operation = :operation
        order by created desc, key limit :limit offset :offset
        """
    )
    suspend fun list(
        operation: ObjectBackupOperation,
        sourceId: String,
        destinationId: String,
        scope: String,
        offset: Long,
        limit: Int,
    ): List<ObjectBackupRun>

    /** Keeps last success independent of a newer partial or cancelled attempt. */
    @Query(
        """
        select * from backup.object_runs
        where source_id = :sourceId and destination_id = :destinationId and scope = :scope and status = 'completed'
            and operation = :operation
        order by created desc, key limit 1
        """
    )
    suspend fun lastSuccessful(
        operation: ObjectBackupOperation,
        sourceId: String,
        destinationId: String,
        scope: String,
    ): ObjectBackupRun?

    /** Lists only the run's required checkpoints in declared inventory order. */
    @Query(
        """
        select p.* from backup.object_run_items i
        join backup.object_progress p on p.key = i.progress_key
        where i.run_key = :key order by i.position limit :limit offset :offset
        """
    )
    suspend fun items(key: String, offset: Long, limit: Int): List<ObjectBackupProgress>

    /** Summarizes current shared checkpoint state, independently of a frozen run result. */
    @Query(OBJECT_BACKUP_RUN_SUMMARY)
    suspend fun summary(key: String): ObjectBackupRunSummary?

    /** Starts one pending or retryable run; stale/active/completed records do not match. */
    @Query(
        """
        update backup.object_runs
        set status = 'running', attempts = attempts + 1, error = null, result = null,
            version = version + 1, modified = now()
        where key = :key and version = :expectedVersion and status in ('pending', 'partial', 'failed')
        returning *
        """
    )
    suspend fun start(key: String, expectedVersion: Long): ObjectBackupRun?

    /** Explicitly queues another attempt without treating cancelled delivery as a retry request. */
    @Query(
        """
        update backup.object_runs
        set status = 'pending', error = null, result = null, version = version + 1, modified = now()
        where key = :key and version = :expectedVersion and status in ('partial', 'failed', 'cancelled')
        returning *
        """
    )
    suspend fun retry(key: String, expectedVersion: Long): ObjectBackupRun?

    /**
     * Freezes an outcome and checks the current attempt and full declared membership atomically.
     * Success requires every item completed; failures with completed items become partial.
     */
    @Query(
        "with summary as (" + OBJECT_BACKUP_RUN_SUMMARY + ") " +
            """
            update backup.object_runs r
            set status = case
                    when :cancelled then 'cancelled'
                    when cast(:error as text) is null then 'completed'
                    when s.completed > 0 then 'partial'
                    else 'failed'
                end,
                error = :error, result = to_jsonb(s), version = r.version + 1, modified = now()
            from summary s
            where r.key = :key and r.version = :expectedVersion
                and (r.status = 'running' or (:cancelled and r.status = 'pending'))
                and s.total = jsonb_array_length(r.inventory->'objects')
                and (cast(:error as text) is not null or s.completed = s.total)
            returning r.*
            """
    )
    suspend fun finish(key: String, expectedVersion: Long, error: String?, cancelled: Boolean): ObjectBackupRun?
}

private const val OBJECT_BACKUP_RUN_SUMMARY = """
    select count(p.key) as total,
        count(p.key) filter (where p.status = 'pending') as pending,
        count(p.key) filter (where p.status = 'running') as running,
        count(p.key) filter (where p.status = 'completed') as completed,
        count(p.key) filter (where p.status = 'failed') as failed,
        coalesce(sum((p.payload->>'length')::bigint), 0)::bigint as "totalBytes",
        coalesce(sum(p.verified_bytes), 0)::bigint as "verifiedBytes"
    from backup.object_runs r
    left join backup.object_run_items i on i.run_key = r.key
    left join backup.object_progress p on p.key = i.progress_key
    where r.key = :key
    group by r.key
"""
