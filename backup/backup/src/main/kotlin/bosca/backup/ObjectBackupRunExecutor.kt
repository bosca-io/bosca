package bosca.backup

import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.DelayException
import kotlin.time.Duration.Companion.seconds

/** Runs persisted backup/restore intent through the owning service and selected application stores. */
@JobDefinition(ObjectBackupRunJob::class, BackupJobQueueNames.backupJobQueue, "object-backup-run")
class ObjectBackupRunExecutor(
    private val service: ObjectBackupRunService,
) : AbstractJobExecutor<ObjectBackupRunJob>(ObjectBackupRunJob.serializer()) {
    override suspend fun getLockId(): String = "object-backup-run:" + getJobDefinition().key

    override suspend fun execute() {
        val definition = getJobDefinition()
        try {
            service.executeConfigured(definition.key)
        } catch (busy: ObjectBackupDestinationBusyException) {
            throw DelayException(5.seconds).also { it.initCause(busy) }
        }
    }
}
