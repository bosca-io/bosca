package bosca.recommendations.jobs

import bosca.artifacts.model.ArtifactType
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.di.provide
import bosca.queue.annotations.JobDefinition
import bosca.recommendations.configuration.JobQueueNames
import bosca.sharedqueue.jobs.AbstractJobExecutor

/** Retries artifact cleanup independently of the committed history deletion. */
@JobDefinition(DeleteContextModelArtifactsJob::class, JobQueueNames.recommendationsJobQueue, "delete-context-model-artifacts")
class DeleteContextModelArtifactsJobExecutor : AbstractJobExecutor<DeleteContextModelArtifactsJob>(DeleteContextModelArtifactsJob.serializer()) {
    override suspend fun execute() {
        val definition = getJobDefinition()
        val artifacts = provide<ArtifactRepositoryService>()
        for (kind in listOf("content", "personalized")) {
            val repository = artifacts.findRepository("model", "recommender-${definition.contextId}-$kind", ArtifactType.ML) ?: continue
            val version = artifacts.findVersion(repository.id, definition.version.toString()) ?: continue
            artifacts.deleteVersion(version.id)
        }
    }
}
