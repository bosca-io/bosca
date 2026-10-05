package bosca.git.ci.graphql

import bosca.db.withConnectionManager
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.LogLine
import bosca.git.service.PipelineJobService
import bosca.git.service.PipelineLogService
import bosca.git.service.PipelineRunService
import bosca.git.service.RepositoryService
import bosca.git.model.PipelineEvent
import bosca.git.graphql.GitEventSubscriptionSupport
import bosca.pubsub.PubSubService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Subscription resolvers for pipeline real-time streams.
 * Flat under `Subscription` per graphql-java requirement.
 */
@TypeController(type = "Subscription")
class GitPipelineSubscription(
    private val logService: PipelineLogService,
    private val jobService: PipelineJobService,
    private val runService: PipelineRunService,
    private val permissionEvaluator: RepositoryPermissionEvaluator,
    private val repositoryService: RepositoryService,
    private val pubSubService: PubSubService,
) : GraphQLController<Any> {

    @Field
    fun pipelineStepLogs(
        authentication: AuthenticationContext,
        repositoryId: UUID,
        stepId: UUID,
    ): Flow<LogLine> = flow {
        withConnectionManager {
            val repository = repositoryService.findById(repositoryId)
                ?: throw NoSuchElementException("Repository not found: $repositoryId")
            permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
            GitPipelineSubscriptionSupport.verifyStepRepository(repositoryId, stepId, jobService, runService)
        }
        emitAll(logService.subscribe(stepId))
    }

    /** Repository-isolated pipeline status activity for IDE and other authenticated clients. */
    @Field
    fun gitPipelineEvents(
        authentication: AuthenticationContext,
        repositoryId: UUID,
    ): Flow<PipelineEvent> = flow {
        withConnectionManager {
            GitEventSubscriptionSupport.verifyView(authentication, repositoryId, repositoryService, permissionEvaluator)
        }
        emitAll(
            GitEventSubscriptionSupport.repositoryEvents(
                pubSubService.subscribe(PIPELINE_EVENT_CHANNEL, PipelineEvent.serializer()),
                repositoryId,
            ),
        )
    }

    companion object {
        private const val PIPELINE_EVENT_CHANNEL = "bosca.git.pipeline"
    }
}

/** Ensures a caller cannot pair an authorized repository with a step from a different repository. */
internal object GitPipelineSubscriptionSupport {
    suspend fun verifyStepRepository(
        repositoryId: UUID,
        stepId: UUID,
        jobService: PipelineJobService,
        runService: PipelineRunService,
    ) {
        val step = jobService.findStepById(stepId)
            ?: throw NoSuchElementException("Step not found: $stepId")
        val job = jobService.findById(step.pipelineJobId)
            ?: throw NoSuchElementException("Job not found: ${step.pipelineJobId}")
        val run = runService.findById(job.pipelineRunId)
            ?: throw NoSuchElementException("Run not found: ${job.pipelineRunId}")
        if (run.repositoryId != repositoryId) {
            throw NoSuchElementException("Step not found: $stepId")
        }
    }
}
