package bosca.backup

import bosca.di.annotation.Provider
import bosca.di.annotation.ProviderName
import bosca.di.annotation.Providers
import bosca.di.ObjectProvider
import bosca.lock.DistributedLockFactory
import bosca.observability.ErrorCapture
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner

/**
 * Queue name constants for backup operations. Backups use a dedicated queue
 * so that long-running export and import jobs do not block content processing.
 */
object BackupJobQueueNames {
    const val backupJobQueue = "backupQueue"
    const val backupRunner = "backupQueueRunner"
    const val backupQueue = "backup"
}

/**
 * Configures the DI providers for the backup job queue and its runner.
 * The queue is backed by the platform's shared queue infrastructure
 * (NATS or Redis) under the "backup" subject namespace.
 */
@Providers
class BackupConfiguration {

    /** Provides the job queue used to dispatch backup and restore jobs. */
    @Provider(singleton = true, name = BackupJobQueueNames.backupJobQueue)
    fun backupJobQueue(factory: JobQueueFactory): JobQueue = factory.create(BackupJobQueueNames.backupQueue)

    /** Provides the job runner that dequeues and executes backup and restore jobs. */
    @Provider(singleton = true, name = BackupJobQueueNames.backupRunner)
    fun backupJobQueueRunner(
        @ProviderName(BackupJobQueueNames.backupJobQueue)
        queue: JobQueue,
        distributedLockFactory: DistributedLockFactory,
        errorCapture: ObjectProvider<ErrorCapture>,
    ): JobRunner = JobRunner(
        queue,
        10,
        distributedLockFactory,
        errorCapture,
    )
}
