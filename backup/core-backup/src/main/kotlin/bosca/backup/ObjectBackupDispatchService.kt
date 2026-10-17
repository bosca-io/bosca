package bosca.backup

import bosca.service.Service

/** Durable publication requests for configured object runs, independent of transfer execution. */
interface ObjectBackupDispatchService : Service {
    /**
     * Records and publishes the observed pending run version in the caller's database context.
     * Requires its fixed storage mapping. Repeating the same version returns the same queue ID.
     * Publication follows the caller's commit; rollback removes both request and delivery.
     * A publication failure after commit leaves the request available to [recover].
     */
    suspend fun request(key: String, expectedVersion: Long): ObjectBackupDispatch

    /** Reads one version's immutable request in the caller's database context. */
    suspend fun get(key: String, version: Long): ObjectBackupDispatch?

    /**
     * Republishes a page of requests whose exact run version is still pending, returning its size.
     * Owns a short database context and requires no active caller transaction. Uses stable queue
     * IDs without replacing existing job state; failures and cancellation propagate. Pagination
     * uses offset and limit. Automatic maintenance scheduling is owned by the caller.
     */
    suspend fun recover(offset: Long = 0, limit: Int = 100): Int
}
