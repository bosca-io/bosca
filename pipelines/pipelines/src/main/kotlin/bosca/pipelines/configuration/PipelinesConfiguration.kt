package bosca.pipelines.configuration

import bosca.db.migrations.Migration
import bosca.di.ObjectProvider
import bosca.di.annotation.Provider
import bosca.di.annotation.ProviderName
import bosca.di.annotation.Providers
import bosca.installer.service.PackageInstaller
import bosca.lock.DistributedLockFactory
import bosca.observability.ErrorCapture
import bosca.pipelines.PipelineEventDispatcher
import bosca.pipelines.git.PipelineProjectContentValidator
import bosca.pipelines.installer.PipelineScheduledJobsInstaller
import bosca.pipelines.node.PipelineRunDriveListener
import bosca.pipelines.repository.PipelinesMigration
import bosca.pipelines.security.PipelinePermissionEvaluator
import bosca.pipelines.service.PipelineService
import bosca.pipelines.trigger.PipelineEventDispatcherImpl
import bosca.pipelines.trigger.PipelineRunDriveListenerImpl
import bosca.scheduler.service.SchedulerService
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import bosca.server.BoscaApplication
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object PipelinesJobQueueNames {
    const val jobQueue = "pipelinesJobQueue"
    const val runner = "pipelinesQueueRunner"
    const val queue = "pipelines"
}

/** Runtime configuration (application config key `pipelines`). */
@Serializable
data class PipelinesRuntimeConfiguration(
    /** Principal identifier triggered pipelines run as (their auth context on the runner). */
    val serviceAccount: String = "sa",
    /**
     * How long a run may stay [bosca.pipelines.model.PipelineRunStatus.SUSPENDED] before the sweeper
     * fails it — a backstop for backing work that never completes. Default 24h.
     */
    val suspendedRunMaxLifetimeMinutes: Long = 1440,
    /**
     * How long an on-demand (manual / API) run may block waiting for a suspended run to resolve before
     * the caller gets the run handle back instead (follow-on / "durable + block briefly").
     * Most on-demand runs finish in the first pass and never block; this only bounds the wait when one
     * parks on a timer. Default 5s.
     */
    val onDemandRunMaxBlockMillis: Long = 5000,
    /**
     * Retention: terminal run-state rows are soft-deleted once they are
     * this many days old (the live `pipeline_run` record; dead-letter triage should happen within this
     * window). Default 30 days.
     */
    val runStateRetentionDays: Long = 30,
    /**
     * Retention: run-history (`pipeline_run_log`) rows are deleted once
     * finished this many days ago. Kept longer than run-state since it is the compact audit trail.
     * Default 90 days.
     */
    val runHistoryRetentionDays: Long = 90,
)

/** DI wiring for the pipelines module. */
@Providers
class PipelinesConfiguration {

    // Migration providers must be NAMED: an unnamed @Provider registers by TYPE, and the type slot
    // holds a single provider — so it silently clobbers (or is clobbered by) other modules'
    // migrations. findAll(Migration::class) aggregates all named providers.
    @Provider(name = "pipelines-migrations")
    fun migration(): Migration = PipelinesMigration()

    // The run job's drive hook ("run = a job"): registered under the
    // PipelineRunDriveListener marker so the run-job wiring attaches it by that core type. When a child
    // backing job reaches a terminal status, it resumes the run from the parked node and enqueues the
    // next backing job as the next child — the run job stays open until the run is done.
    @Provider(singleton = true)
    fun pipelineRunDriveListener(json: Json): PipelineRunDriveListener =
        PipelineRunDriveListenerImpl(json)

    // Registers the suspended-run sweeper as a cron-scheduled job.
    @Provider(singleton = true)
    fun pipelineScheduledJobsInstaller(
        schedulerService: SchedulerService,
        json: Json,
    ): PackageInstaller = PipelineScheduledJobsInstaller(schedulerService, json)

    @Provider(singleton = true)
    fun pipelinesRuntimeConfiguration(application: BoscaApplication): PipelinesRuntimeConfiguration {
        return application.environment.config.propertyOrNull("pipelines")
            ?.getAs<PipelinesRuntimeConfiguration>()
            ?: PipelinesRuntimeConfiguration()
    }

    @Provider(singleton = true, name = PipelinesJobQueueNames.jobQueue)
    fun pipelinesJobQueue(factory: JobQueueFactory): JobQueue = factory.create(PipelinesJobQueueNames.queue)

    @Provider(singleton = true, name = PipelinesJobQueueNames.runner)
    fun pipelinesJobQueueRunner(
        @ProviderName(PipelinesJobQueueNames.jobQueue)
        queue: JobQueue,
        distributedLockFactory: DistributedLockFactory,
        errorCapture: ObjectProvider<ErrorCapture>,
    ): JobRunner = JobRunner(
        queue,
        100,
        distributedLockFactory,
        errorCapture,
    )

    @Provider(singleton = true)
    fun pipelineEventDispatcher(
        pipelineService: PipelineService,
        json: Json,
    ): PipelineEventDispatcher = PipelineEventDispatcherImpl(pipelineService, json)

    @Provider(singleton = true)
    fun pipelineProjectContentValidator(
        pipelineService: PipelineService,
    ): PipelineProjectContentValidator = PipelineProjectContentValidator(pipelineService)

    @Provider(singleton = true)
    fun pipelinePermissionEvaluator(
        service: PipelineService,
        securityService: SecurityService,
        groupEvaluator: GroupEvaluator,
    ) = PipelinePermissionEvaluator(service, securityService, groupEvaluator)
}
