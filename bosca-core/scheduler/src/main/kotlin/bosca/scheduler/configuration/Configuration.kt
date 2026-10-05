package bosca.scheduler.configuration

import bosca.db.migrations.Migration
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.lock.DistributedLockFactory
import bosca.scheduler.listeners.JobEnqueueEventForwarder
import bosca.scheduler.runner.SchedulerRunner
import bosca.scheduler.service.SchedulerService
import bosca.security.service.SecurityService
import bosca.sharedqueue.jobs.enqueue.JobEnqueueEventChannel
import bosca.server.BoscaApplication
import kotlinx.serialization.Serializable

/**
 * Configuration for enabling/disabling the scheduler runner.
 */
@Serializable
data class SchedulerConfiguration(
    val enabled: Boolean = false,
    val evaluationIntervalSeconds: Int = 10
)

@Providers
class SchedulerConfigurationProvider {

    @Provider(singleton = true)
    fun schedulerConfiguration(application: BoscaApplication): SchedulerConfiguration {
        val config = application.environment.config
        return config.propertyOrNull("scheduler")?.getAs<SchedulerConfiguration>() ?: SchedulerConfiguration()
    }

    @Provider(name = "scheduler-migrations")
    fun migration(): Migration = SchedulerMigration()

    @Provider(singleton = true)
    fun runner(
        service: SchedulerService,
        distributedLockFactory: DistributedLockFactory,
        securityService: SecurityService,
    ) = SchedulerRunner(service, distributedLockFactory, securityService)

    @Provider
    fun executionListener(channel: JobEnqueueEventChannel) = bosca.scheduler.listeners.ScheduledJobExecutionListener(channel)

    @Provider(singleton = true)
    fun jobEnqueueEventForwarder(channel: JobEnqueueEventChannel, service: SchedulerService) = JobEnqueueEventForwarder(channel, service)
}
