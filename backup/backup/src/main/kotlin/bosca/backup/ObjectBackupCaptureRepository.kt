package bosca.backup

import bosca.db.annotation.Query
import bosca.db.annotation.Repository

/** Stores immutable selections independently of the existing per-revision transfer ledger. */
@Repository
interface ObjectBackupCaptureRepository {
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
        insert into backup.object_capture_plans (key, destination_id, inventory, payload_prefix, path)
        values (:key, :destinationId, :inventory, :payloadPrefix, :path)
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
