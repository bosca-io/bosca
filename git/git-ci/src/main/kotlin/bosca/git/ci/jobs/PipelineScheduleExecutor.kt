package bosca.git.ci.jobs

import bosca.di.provide
import bosca.git.ci.parser.ExpressionContext
import bosca.git.ci.parser.PipelineExpressionParser
import bosca.git.ci.parser.PipelineJobFilter
import bosca.git.ci.parser.PipelineYamlParser
import bosca.git.model.CommitStatusState
import bosca.git.model.PipelineScheduleJob
import bosca.git.model.PipelineTriggerType
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.CommitStatusService
import bosca.git.service.PipelineRunService
import bosca.git.service.PipelineScheduleRunner
import bosca.git.service.PipelineScheduleService
import bosca.git.service.PipelineService
import bosca.git.service.RepositoryBrowseService
import bosca.git.service.RepositoryService
import bosca.queue.annotations.JobDefinition
import bosca.scheduler.model.ScheduledJobExecutionContext
import bosca.scheduler.model.ScheduledJobPrincipalState
import bosca.scheduler.service.SchedulerService
import bosca.security.model.PermissionAction
import bosca.security.service.SecurityService
import bosca.security.service.impersonate
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.sharedqueue.jobs.AbstractJobExecutor
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/** Dispatch target used by the platform scheduler for a due Git pipeline cron. */
@JobDefinition(PipelineScheduleJob::class, "git", PipelineScheduleJob.NAME)
class PipelineScheduleExecutor : AbstractJobExecutor<PipelineScheduleJob>(PipelineScheduleJob.serializer()) {
    override suspend fun execute() {
        val definition = getJobDefinition()
        val context = Json.decodeFromJsonElement(ScheduledJobExecutionContext.serializer(), getJobContext())
        val principalId = context.executionPrincipalId
            ?: throw IllegalStateException("Scheduled pipeline execution has no principal")
        provide<PipelineScheduleRunner>().run(context.scheduledJobId, definition.pipelineId, principalId)
    }
}

/** Revalidates the scheduler-owned identity before creating the attributed Git pipeline run. */
@ServiceImplementation
class PipelineScheduleRunnerImpl(
    private val scheduleService: PipelineScheduleService,
    private val schedulerService: SchedulerService,
    private val pipelineService: PipelineService,
    private val repositoryService: RepositoryService,
    private val browseService: RepositoryBrowseService,
    private val runService: PipelineRunService,
    private val commitStatusService: CommitStatusService,
    private val securityService: SecurityService,
    private val permissionEvaluator: RepositoryPermissionEvaluator,
) : PipelineScheduleRunner {

    override suspend fun run(scheduledJobId: UUID, pipelineId: UUID, executionPrincipalId: UUID) {
        val scheduledJob = scheduleService.findById(scheduledJobId) ?: return
        if (scheduledJob.principalState != ScheduledJobPrincipalState.ACTIVE ||
            scheduledJob.executionPrincipalId != executionPrincipalId
        ) {
            return
        }
        val principal = securityService.getPrincipalById(executionPrincipalId)
        if (principal == null || principal.deletedAt != null) {
            schedulerService.parkNeedsPrincipal(scheduledJob.id)
            return
        }

        val payload = runCatching {
            Json.decodeFromJsonElement(PipelineScheduleJob.serializer(), scheduledJob.jobParameters)
        }.getOrNull()
        if (scheduledJob.jobName != PipelineScheduleJob.NAME ||
            payload?.pipelineId != pipelineId
        ) {
            return
        }
        val pipeline = pipelineService.findById(pipelineId)
        if (pipeline == null || pipeline.deletedAt != null) {
            scheduleService.delete(scheduledJob.id)
            return
        }
        val repository = repositoryService.findById(pipeline.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${pipeline.repositoryId}")
        val authentication = securityService.impersonate(executionPrincipalId)
        if (!permissionEvaluator.isAllowed(authentication, repository, PermissionAction.EXECUTE)) {
            schedulerService.parkNeedsPrincipal(scheduledJob.id)
            return
        }

        val ref = "refs/heads/${repository.defaultBranch}"
        val commitSha = browseService.resolveRef(repository.id, ref)
            ?: throw NoSuchElementException("Ref not found: $ref")
        val definition = pipelineService.parseDefinition(repository.id, commitSha, pipeline.filePath)
            ?: throw IllegalStateException("Failed to parse pipeline: ${pipeline.filePath}")
        val matchingTrigger = definition.triggers.any {
            it.type == PipelineTriggerType.SCHEDULE && it.cron?.trim() == scheduledJob.cronExpression
        }
        if (!matchingTrigger) {
            scheduleService.delete(scheduledJob.id)
            return
        }
        val errors = PipelineYamlParser().validate(definition)
        check(errors.isEmpty()) {
            "Scheduled pipeline '${pipeline.name}' is invalid: ${errors.joinToString("; ")}"
        }

        val filteredJobs = PipelineJobFilter.filter(
            definition.jobs,
            ExpressionContext(ref = ref, branch = repository.defaultBranch, event = "schedule"),
            PipelineExpressionParser(),
        )
        if (filteredJobs.isEmpty()) {
            log.info("All jobs skipped by if conditions for scheduled pipeline '{}'", pipeline.name)
            return
        }
        val run = runService.createRun(
            pipelineId = pipeline.id,
            repositoryId = repository.id,
            definition = definition.copy(jobs = filteredJobs),
            commitSha = commitSha,
            ref = ref,
            triggerType = PipelineTriggerType.SCHEDULE,
            triggeredBy = executionPrincipalId,
        )
        for (jobName in filteredJobs.keys) {
            commitStatusService.recordStatus(
                repositoryId = repository.id,
                commitSha = commitSha,
                context = "ci/${pipeline.name.lowercase().replace(' ', '-')}/$jobName",
                state = CommitStatusState.PENDING,
                description = "Queued",
            )
        }
        log.info(
            "Created scheduled pipeline run #{} for '{}' as principal {}",
            run.number,
            pipeline.name,
            executionPrincipalId,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(PipelineScheduleRunnerImpl::class.java)
    }
}
