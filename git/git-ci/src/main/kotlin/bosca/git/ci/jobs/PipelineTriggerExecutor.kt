package bosca.git.ci.jobs

import bosca.db.transaction
import bosca.di.provide
import bosca.git.ci.parser.ExpressionContext
import bosca.git.ci.parser.PipelineExpressionParser
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
        // Refresh the current catalog independently of build permission; match this commit's snapshot.
        val pipelines = pipelineService.syncPipelines(job.repositoryId, job.ref, job.afterSha)
        val principalId = job.pusherPrincipalId
        if (principalId == null) {
            log.info("Skipping CI for repository {}: the ref update has no initiating principal", job.repositoryId)
            return
        }
        val security = provide<SecurityService>()
        val principal = security.getPrincipalById(principalId)
        if (principal == null || principal.deletedAt != null) {
            log.info("Skipping CI for repository {}: initiating principal {} is unavailable", job.repositoryId, principalId)
            return
        }
        val authentication = security.impersonate(principalId)
        if (!provide<RepositoryPermissionEvaluator>().isAllowed(authentication, repository, PermissionAction.EXECUTE)) {
            log.info("Skipping CI for repository {}: principal {} cannot execute builds", job.repositoryId, principalId)
            return
        }
        val runService = provide<PipelineRunService>()
        val commitStatusService = provide<CommitStatusService>()
        val triggerEvaluator = TriggerEvaluator()
        val parser = PipelineYamlParser()
        val expressionParser = PipelineExpressionParser()

        val isTag = job.ref.startsWith("refs/tags/")

        val pushEvent = if (!isTag) PushEvent(
            repositoryId = job.repositoryId,
            ref = job.ref,
            beforeSha = job.beforeSha,
            afterSha = job.afterSha,
            pusherPrincipalId = job.pusherPrincipalId
        ) else null

        val tagName = if (isTag) job.ref.removePrefix("refs/tags/") else null
        val branchName = if (job.ref.startsWith("refs/heads/")) job.ref.removePrefix("refs/heads/") else ""

        val expressionContext = ExpressionContext(
            ref = job.ref,
            branch = branchName,
            event = if (isTag) "tag" else "push"
        )

        val triggerType = if (isTag) PipelineTriggerType.TAG else PipelineTriggerType.PUSH

        // A failing pipeline must not block the others matched by this push: every pipeline is
        // attempted, then the job fails so redelivery retries only those without an occurrence.
        // A rejected plan is a definition problem that retrying cannot fix, so it is only logged.
        var failure: Exception? = null
        for (pipeline in pipelines) {
            val matchingTrigger = if (isTag) {
                triggerEvaluator.evaluateTag(pipeline, tagName ?: continue)
            } else {
                triggerEvaluator.evaluatePush(pipeline, pushEvent ?: continue)
            }
            if (matchingTrigger == null) continue

            try {
                val definition = pipelineService.parseDefinition(
                    job.repositoryId, job.afterSha, pipeline.filePath
                ) ?: continue

                val errors = parser.validate(definition)
                if (errors.isNotEmpty()) {
                    log.warn("Skipping pipeline {} due to validation errors: {}", pipeline.name, errors)
                    continue
                }

                // Shared creation-time selection (also applied inside createRun): conditions
                // evaluated, needs-edges to excluded jobs pruned.
                val filteredJobs = bosca.git.ci.parser.PipelineJobFilter.filter(
                    definition.jobs, expressionContext, expressionParser,
                )

                if (filteredJobs.isEmpty()) {
                    log.info("All jobs skipped by if conditions for pipeline '{}'", pipeline.name)
                    continue
                }

                val filteredDefinition = definition.copy(jobs = filteredJobs)

                val run = transaction {
                    val created = runService.createTriggeredRun(
                        triggerId = bosca.sharedqueue.jobs.job().getId(),
                        pipelineId = pipeline.id,
                        repositoryId = job.repositoryId,
                        definition = filteredDefinition,
                        commitSha = job.afterSha,
                        ref = job.ref,
                        triggerType = triggerType,
                        triggeredBy = principalId
                    ) ?: return@transaction null

                    for ((jobName, _) in filteredJobs) {
                        val context = "ci/${pipeline.name.lowercase().replace(' ', '-')}/$jobName"
                        commitStatusService.recordStatus(
                            repositoryId = job.repositoryId,
                            commitSha = job.afterSha,
                            context = context,
                            state = CommitStatusState.PENDING,
                            description = "Queued"
                        )
                    }
                    created
                } ?: continue

                log.info("Created pipeline run #{} for '{}' ({}/{} jobs) triggered by {} on {}",
                    run.number, pipeline.name, filteredJobs.size, definition.jobs.size,
                    if (isTag) "tag" else "push", job.ref)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PipelinePlanRejectedException) {
                log.warn("Skipping pipeline '{}': {}", pipeline.name, e.message)
            } catch (e: Exception) {
                log.error("Failed to create pipeline run for '{}': {}", pipeline.name, e.message, e)
                val first = failure
                if (first == null) failure = e else first.addSuppressed(e)
            }
        }
        failure?.let { throw it }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PipelineTriggerExecutor::class.java)
    }
}
