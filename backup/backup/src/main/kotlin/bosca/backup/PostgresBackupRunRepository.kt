package bosca.backup

import bosca.db.annotation.Query
import bosca.db.annotation.Repository

/** Journals native database requests and shares ownership with existing configured object writers. */
@Repository
interface PostgresBackupRunRepository {
    /** Reads fixed request intent and the latest attempt outcome. */
    @Query("select * from backup.postgres_runs where key = :key")
    suspend fun get(key: String): PostgresBackupRun?

    /** Lists one exact source/storage scope with offset pagination. */
    @Query("""
        select * from backup.postgres_runs where source_id = :sourceId and storage_key = :storageKey
        order by created desc, key limit :limit offset :offset
    """)
    suspend fun list(sourceId: String, storageKey: String, offset: Long, limit: Int): List<PostgresBackupRun>

    /** Inserts intent without replacing an existing request. */
    @Query("""
        insert into backup.postgres_runs (key, source_id, storage_key, prefix)
        values (:key, :sourceId, :storageKey, :prefix) on conflict (key) do nothing
    """, returnUpdateCount = true)
    suspend fun add(key: String, sourceId: String, storageKey: String, prefix: String): Int

    /** Starts only an unfinished idle request and records its exclusive execution token. */
    @Query("""
        update backup.postgres_runs set status = 'running', writer_token = :token,
            attempts = attempts + 1, error = null, modified = now(), version = version + 1
        where key = :key and status in ('pending', 'failed', 'cancelled')
    """, returnUpdateCount = true)
    suspend fun begin(key: String, token: String): Int

    /** Restarts only the original job's running request with a new token and independent capture. */
    @Query("""
        update backup.postgres_runs set writer_token = :token,
            attempts = attempts + 1, modified = now(), version = version + 1
        where key = :key and status = 'running' and strpos(writer_token, ':') > 0
            and split_part(writer_token, ':', 1) = :checkpointKey
    """, returnUpdateCount = true)
    suspend fun resume(key: String, checkpointKey: String, token: String): Int

    /** Claims the configured destination without taking over another owner. */
    @Query("""
        insert into backup.object_store_writers (storage_key, postgres_key)
        values (:storageKey, :key) on conflict (storage_key) do update
            set postgres_key = excluded.postgres_key
            where backup.object_store_writers.postgres_key = excluded.postgres_key
    """, returnUpdateCount = true)
    suspend fun claim(key: String, storageKey: String): Int

    /** Publishes a receipt only for the matching running execution. */
    @Query("""
        update backup.postgres_runs set status = 'completed', writer_token = null, result = :result,
            modified = now(), version = version + 1
        where key = :key and status = 'running' and writer_token = :token
    """, returnUpdateCount = true)
    suspend fun complete(key: String, token: String, result: PostgresBackupReceipt): Int

    /** Records failure or cancellation only for the matching execution token. */
    @Query("""
        update backup.postgres_runs set status = :status, writer_token = null, error = :error,
            modified = now(), version = version + 1
        where key = :key and status = 'running' and writer_token = :token
    """, returnUpdateCount = true)
    suspend fun fail(key: String, token: String, status: PostgresBackupRunStatus, error: String): Int

    /** Releases this request after its token-fenced state change in the same transaction. */
    @Query("delete from backup.object_store_writers where postgres_key = :key", returnUpdateCount = true)
    suspend fun release(key: String): Int
}
