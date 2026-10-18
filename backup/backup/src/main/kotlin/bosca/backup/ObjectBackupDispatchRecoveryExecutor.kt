package bosca.backup

import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.configuration.JobQueueNames
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Sweeps saved requests through the common queue while backup workers perform transfers. */
@JobDefinition(
    ObjectBackupDispatchRecoveryJob::class,
    JobQueueNames.commonJobQueue,
    ObjectBackupDispatchRecoveryJob.NAME,
    displayName = "Recover Object Backup Dispatches",
)
class ObjectBackupDispatchRecoveryExecutor(
    private val dispatches: ObjectBackupDispatchService,
) : AbstractJobExecutor<ObjectBackupDispatchRecoveryJob>(ObjectBackupDispatchRecoveryJob.serializer()) {
    override suspend fun execute() {
        val pageSize = getJobDefinition().pageSize
        var offset = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            if (dispatches.recover(offset, pageSize) < pageSize) return
            offset += pageSize
        }
    }
}
