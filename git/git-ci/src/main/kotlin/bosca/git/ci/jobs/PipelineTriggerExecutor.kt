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
        val evaluationId = bosca.sharedqueue.jobs.job().getId()
        val evaluation = pipelineService.beginEvaluation(bosca.git.model.PipelineEvaluation(
            id = evaluationId, repositoryId = job.repositoryId, commitSha = job.afterSha, ref = job.ref,
        ))
        if (evaluation.completed) return
        // evaluate() records its own per-pipeline failures; this only covers failures before it can.
        val failure = try {
            evaluate(job, repository, pipelineService, evaluationId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            pipelineService.finishEvaluation(evaluationId, completed = false, error = e.message ?: "CI evaluation failed")
            throw e
        }
        failure?.let { throw it }
    }

    private suspend fun evaluate(
        job: PipelineTriggerJob,
        repository: bosca.git.model.Repository,
        pipelineService: PipelineService,
        evaluationId: bosca.serialization.UUID,
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
                    pipelineService.recordEvaluationRun(evaluationId, pipeline.filePath, null)
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
                    pipelineService.recordEvaluationRun(evaluationId, pipeline.filePath, null)
                    continue
                }
                transaction {
                    val created = runService.createTriggeredRun(
                        evaluationId, pipeline.id, job.repositoryId, definition, job.afterSha,
                        job.ref, triggerType, actor,
                    ) ?: return@transaction
                    // The occurrence, run and selection link commit atomically. A retry retains the link.
                    pipelineService.recordEvaluationRun(evaluationId, pipeline.filePath, created.id)
                    for (jobName in plan.jobs.keys) {
                        commitStatusService.recordStatus(
                            repositoryId = job.repositoryId,
                            commitSha = job.afterSha,
                            context = "ci/${pipeline.name.lowercase().replace(' ', '-')}/$jobName",
                            state = CommitStatusState.PENDING,
                            description = "Queued",
                        )
                    }
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
        pipelineService.finishEvaluation(evaluationId, completed = failure == null, error = errors.takeIf { it.isNotEmpty() }?.joinToString("; "))
        return failure
    }

    companion object {
        private val log = LoggerFactory.getLogger(PipelineTriggerExecutor::class.java)
    }
}
