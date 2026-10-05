package bosca.experimentation.configuration

import bosca.db.migrations.Migration
import bosca.di.ObjectProvider
import bosca.di.annotation.Provider
import bosca.di.annotation.ProviderName
import bosca.di.annotation.Providers
import bosca.lock.DistributedLockFactory
import bosca.observability.ErrorCapture
import bosca.server.BoscaApplication
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner

@Providers
class Configuration {

    @Provider(singleton = true)
    fun experimentationConfig(application: BoscaApplication): ExperimentationConfig {
        return application.environment.config.propertyOrNull("experimentation")?.getAs()
            ?: ExperimentationConfig()
    }

    @Provider(name = "experimentation-migrations")
    fun migration(): Migration = ExperimentationMigration()

    @Provider(singleton = true, name = JobQueueNames.experimentationJobQueue)
    fun experimentationJobQueue(factory: JobQueueFactory): JobQueue = factory.create(JobQueueNames.experimentationQueue)

    @Provider(name = JobQueueNames.experimentationRunner)
    fun experimentationJobQueueRunner(
        @ProviderName(JobQueueNames.experimentationJobQueue)
        queue: JobQueue,
        distributedLockFactory: DistributedLockFactory,
        errorCapture: ObjectProvider<ErrorCapture>,
    ): JobRunner = JobRunner(queue, 100, distributedLockFactory, errorCapture)
}
