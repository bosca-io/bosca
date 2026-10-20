package bosca.backup

import bosca.db.annotation.Query
import bosca.db.annotation.Repository

/** Stores immutable selections independently of the existing per-revision transfer ledger. */
@Repository
interface ObjectBackupCaptureRepository {
    /** Claims the retained destination without replacing a run or another capture execution. */
    @Query(
        """
        insert into backup.object_store_writers (storage_key, capture_key, capture_token)
        values (:storageKey, :key, :token) on conflict (storage_key) do nothing
        """,
        returnUpdateCount = true,
    )
    suspend fun claimWriter(key: String, storageKey: String, token: String): Int

    /** Rotates only a redelivered job's own capture token, preserving other destination owners. */
    @Query(
        """
        update backup.object_store_writers set capture_token = :token
        where storage_key = :storageKey and capture_key = :key
            and strpos(capture_token, ':') > 0
            and split_part(capture_token, ':', 1) = :checkpointKey
        """,
        returnUpdateCount = true,
    )
    suspend fun resumeWriter(key: String, storageKey: String, checkpointKey: String, token: String): Int

    /** Reads the active token for one configured capture plan. */
    @Query("select capture_token, capture_key, storage_key from backup.object_store_writers where capture_key = :key")
    suspend fun getWriter(key: String): ObjectBackupCaptureWriter?

    /** Deletes only the observed capture execution, leaving later or unrelated owners untouched. */
    @Query(
        "delete from backup.object_store_writers where capture_key = :key and capture_token = :expectedKey",
        returnUpdateCount = true,
    )
    suspend fun releaseWriter(key: String, expectedKey: String): Int

    /** Inserts one immutable backup binding without replacing a concurrently saved mapping. */
    @Query(
        """
        insert into backup.object_capture_storage (key, backup_key) values (:key, :backupKey)
        on conflict (key) do nothing
        """,
        returnUpdateCount = true,
    )
    suspend fun addStorage(key: String, backupKey: String): Int

    /** Adds one selected logical source binding in the new mapping's transaction. */
    @Query(
        """
        insert into backup.object_capture_stores (plan_key, store, storage_key)
        values (:key, :store, :storageKey)
        """,
        returnUpdateCount = true,
    )
    suspend fun addStore(key: String, store: String, storageKey: String): Int

    /** Reads the fixed logical mapping with strongly typed JSONB map decoding. */
    @Query(
        """
        select s.*, coalesce((
            select jsonb_object_agg(m.store, m.storage_key)
            from backup.object_capture_stores m where m.plan_key = s.key
        ), '{}'::jsonb) as stores
        from backup.object_capture_storage s where s.key = :key
        """,
    )
    suspend fun getStorage(key: String): ObjectBackupCaptureStorage?

    /** Inserts one selected capture intent without changing an existing plan. */
    @Query(
        """
        insert into backup.object_capture_plans (key, destination_id, inventory, payload_prefix, path, discovery)
        values (:key, :destinationId, :inventory, :payloadPrefix, :path, :discovery)
        on conflict (key) do nothing
        """,
        returnUpdateCount = true,
    )
    suspend fun add(plan: ObjectBackupCapturePlan): Int

    /** Reads the selected input and output intent for one capture. */
    @Query("select * from backup.object_capture_plans where key = :key")
    suspend fun get(key: String): ObjectBackupCapturePlan?

    /** Lists plans for an exact source/destination/scope using ordinary offset pagination. */
    @Query(
        """
        select * from backup.object_capture_plans
        where inventory->>'sourceId' = :sourceId and destination_id = :destinationId
            and inventory->>'scope' = :scope
        order by created desc, key limit :limit offset :offset
        """,
    )
    suspend fun list(
        sourceId: String,
        destinationId: String,
        scope: String,
        offset: Long,
        limit: Int,
    ): List<ObjectBackupCapturePlan>
}
