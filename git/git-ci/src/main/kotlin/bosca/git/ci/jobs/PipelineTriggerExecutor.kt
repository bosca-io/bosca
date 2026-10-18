package bosca.git.ci.jobs

import bosca.db.transaction
import bosca.di.provide
import bosca.git.ci.parser.PipelineYamlParser
import bosca.git.ci.trigger.TriggerEvaluator
import bosca.git.model.CommitStatusState
import bosca.git.model.PipelinePlanRejectedException
import bosca.git.model.PipelineTriggerJob
import bosca.git.model.PipelineTriggerType
import bosca.git.model.PushEvent
import bosca.git.service.CommitStatusService
import bosca.git.service.PipelineRunService
import bosca.git.service.PipelineService
import bosca.git.service.RepositoryService
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.SecurityService
import bosca.security.service.impersonate
import kotlinx.coroutines.CancellationException
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import org.slf4j.LoggerFactory

@JobDefinition(PipelineTriggerJob::class, "git", "pipeline-trigger")
class PipelineTriggerExecutor : AbstractJobExecutor<PipelineTriggerJob>(PipelineTriggerJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        val repository = provide<RepositoryService>().findById(job.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${job.repositoryId}")
        val pipelineService = provide<PipelineService>()
        val triggerId = bosca.sharedqueue.jobs.job().getId()
        val statuses = provide<CommitStatusService>()
        statuses.recordStatus(job.repositoryId, job.afterSha, "ci/configuration", CommitStatusState.PENDING,
            "Selecting CI jobs", ref = job.ref)
        // evaluate() records its own per-pipeline failures; this only covers failures before it can.
        val failure = try {
            evaluate(job, repository, pipelineService, triggerId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            statuses.recordStatus(job.repositoryId, job.afterSha, "ci/configuration", CommitStatusState.ERROR,
                e.message ?: "CI selection failed", ref = job.ref)
            throw e
        }
        failure?.let { throw it }
    }

    private suspend fun evaluate(
        job: PipelineTriggerJob,
        repository: bosca.git.model.Repository,
        pipelineService: PipelineService,
        triggerId: bosca.serialization.UUID,
    ): Exception? {
        // Refresh the catalog even when the initiator cannot execute builds.
        val pipelines = pipelineService.syncPipelines(job.repositoryId, job.ref, job.afterSha)
        val principalId = job.pusherPrincipalId
        val security = provide<SecurityService>()
        val principal = principalId?.let { security.getPrincipalById(it) }
        val authorizationError = when {
            principalId == null -> "The ref update has no initiating principal"
            principal == null || principal.deletedAt != null -> "The initiating principal is unavailable"
            !provide<RepositoryPermissionEvaluator>().isAllowed(
                security.impersonate(principalId), repository, PermissionAction.EXECUTE,
            ) -> "The initiating principal cannot execute builds"
            else -> null
        }
        // The authorization error applies only when a pipeline matches; otherwise nothing would have run.
        val actor = principalId.takeIf { authorizationError == null }
        var blocked = false
        val runService = provide<PipelineRunService>()
        val commitStatusService = provide<CommitStatusService>()
        val triggerEvaluator = TriggerEvaluator()
        val parser = PipelineYamlParser()
        val isTag = job.ref.startsWith("refs/tags/")
        val pushEvent = PushEvent(job.repositoryId, job.ref, job.beforeSha, job.afterSha, principalId)
        val triggerType = if (isTag) PipelineTriggerType.TAG else PipelineTriggerType.PUSH
        val errors = pipelineService.definitionErrors(job.repositoryId, job.afterSha).toMutableList()
        var failure: Exception? = null
        for (pipeline in pipelines) {
            try {
                val matchingTrigger = if (isTag) {
                    triggerEvaluator.evaluateTag(pipeline, job.ref.removePrefix("refs/tags/"))
                } else triggerEvaluator.evaluatePush(pipeline, pushEvent)
                if (matchingTrigger == null) {
                    continue
                }
                if (actor == null) {
                    blocked = true
                    continue
                }
                val definition = pipelineService.parseDefinition(job.repositoryId, job.afterSha, pipeline.filePath)
                    ?: throw PipelinePlanRejectedException("${pipeline.filePath}: definition could not be read")
                val validationErrors = parser.validate(definition)
                if (validationErrors.isNotEmpty()) {
                    errors += validationErrors.map { "${pipeline.filePath}: $it" }
                    continue
                }
                // Use the same inputs, conditions and environment selection as run creation.
                val plan = runService.plan(definition, job.ref, triggerType)
                if (plan.jobs.isEmpty()) {
                    continue
                }
                transaction {
                    runService.createTriggeredRun(
                        triggerId, pipeline.id, job.repositoryId, definition, job.afterSha,
                        job.ref, triggerType, actor,
                    ) ?: return@transaction

                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PipelinePlanRejectedException) {
                errors += "${pipeline.filePath}: ${e.message}"
                log.warn("Rejected CI plan for {}: {}", pipeline.filePath, e.message)
            } catch (e: Exception) {
                errors += "${pipeline.filePath}: ${e.message}"
                if (failure == null) failure = e else failure.addSuppressed(e)
            }
        }
        if (blocked && authorizationError != null) {
            log.info("Skipping CI for repository {}: {}", job.repositoryId, authorizationError)
            errors.add(0, authorizationError)
        }
        commitStatusService.recordStatus(job.repositoryId, job.afterSha, "ci/configuration",
            if (errors.isEmpty()) CommitStatusState.SUCCESS else CommitStatusState.ERROR,
            errors.takeIf { it.isNotEmpty() }?.joinToString("; ") ?: "CI jobs selected", ref = job.ref)
        return failure
    }

    companion object {
        private val log = LoggerFactory.getLogger(PipelineTriggerExecutor::class.java)
    }
}
