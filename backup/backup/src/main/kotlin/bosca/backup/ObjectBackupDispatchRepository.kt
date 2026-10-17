package bosca.backup

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

/** Immutable publication intent for one exact operational run version. */
@Repository
interface ObjectBackupDispatchRepository {
    /** Inserts a stable queue ID without replacing an earlier request for this version. */
    @Query(
        """
        insert into backup.object_run_dispatches (key, run_key, version) values (:id, :runKey, :version)
        on conflict (run_key, version) do nothing
        """,
        returnUpdateCount = true,
    )
    suspend fun add(id: UUID, runKey: String, version: Long): Int

    /** Reads a version's original queue identity and creation time. */
    @Query("select * from backup.object_run_dispatches where run_key = :runKey and version = :version")
    suspend fun get(runKey: String, version: Long): ObjectBackupDispatch?

    /** Lists only requests whose pinned version remains pending, in stable offset order. */
    @Query(
        """
        select d.* from backup.object_run_dispatches d
        join backup.object_runs r on r.key = d.run_key and r.version = d.version
        where r.status = 'pending'
        order by d.created, d.key limit :limit offset :offset
        """,
    )
    suspend fun pending(offset: Long, limit: Int): List<ObjectBackupDispatch>
}
