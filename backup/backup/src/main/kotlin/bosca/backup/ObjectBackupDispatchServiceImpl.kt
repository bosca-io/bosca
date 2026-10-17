package bosca.backup

import bosca.db.connectionOrNull
import bosca.db.transaction
import bosca.db.withConnectionManager
import bosca.di.annotation.ProviderName
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.sharedqueue.jobs.JobQueue

/** Records publication intent before the queue's existing commit-deferred enqueue boundary. */
@ServiceImplementation
class ObjectBackupDispatchServiceImpl(
    private val repository: ObjectBackupDispatchRepository,
    private val runs: ObjectBackupRunRepository,
    @ProviderName(BackupJobQueueNames.backupJobQueue)
    private val queue: JobQueue,
) : ObjectBackupDispatchService {
    override suspend fun request(key: String, expectedVersion: Long): ObjectBackupDispatch {
        validate(key, expectedVersion)
        return transaction {
            val run = checkNotNull(runs.getForUpdate(key)) { "Object backup run is missing: $key" }
            check(run.version == expectedVersion && run.status == ObjectBackupRunStatus.PENDING) {
                "Object backup dispatch requires the observed pending run version: $key at $expectedVersion"
            }
            checkNotNull(runs.getStorage(key)) { "Object run storage is not configured: $key" }
            repository.add(UUID.random(), key, expectedVersion)
            val dispatch = checkNotNull(repository.get(key, expectedVersion)) {
                "Object backup dispatch is missing: $key at $expectedVersion"
            }
            publish(dispatch)
            dispatch
        }
    }

    override suspend fun get(key: String, version: Long): ObjectBackupDispatch? {
        validate(key, version)
        return repository.get(key, version)
    }

    override suspend fun recover(offset: Long, limit: Int): Int {
        require(offset >= 0) { "Object backup dispatch offset must not be negative" }
        require(limit > 0) { "Object backup dispatch limit must be positive" }
        check(connectionOrNull()?.inTransaction != true) {
            "Object backup dispatch recovery must run outside an active database transaction"
        }
        val pending = withConnectionManager { repository.pending(offset, limit) }
        for (dispatch in pending) publish(dispatch)
        return pending.size
    }

    private suspend fun publish(dispatch: ObjectBackupDispatch) {
        val job = ObjectBackupRunJob(dispatch.runKey, dispatch.version).prepare {
            setPersistentId(dispatch.key)
        }
        queue.enqueueIfAbsent(job)
    }

    private fun validate(key: String, version: Long) {
        require(key.isNotBlank()) { "Object backup run key is required" }
        require(version >= 0) { "Object backup run version must not be negative" }
    }
}
