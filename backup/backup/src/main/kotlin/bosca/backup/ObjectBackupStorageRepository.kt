package bosca.backup

import bosca.db.annotation.Query
import bosca.db.annotation.Repository

/** Persists immutable configured storage addresses without credentials. */
@Repository
interface ObjectBackupStorageRepository {
    /** Claims both the identity and address without replacing an earlier binding. */
    @Query(
        """
        insert into backup.object_stores (key, type, endpoint, bucket, region, base_path)
        values (:key, :type, :endpoint, :bucket, :region, :basePath)
        on conflict do nothing
        """,
        returnUpdateCount = true,
    )
    suspend fun add(storage: ObjectBackupStorage): Int

    /** Reads the address recorded for a storage identity. */
    @Query("select * from backup.object_stores where key = :key")
    suspend fun get(key: String): ObjectBackupStorage?
}
