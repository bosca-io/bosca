package bosca.content.metadata.jobs

import bosca.content.configuration.JobQueueNames
import bosca.queue.annotations.IJobDefinition
import bosca.queue.annotations.JobDefinition
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.AbstractJobExecutor
import kotlinx.serialization.Serializable

@Serializable
class ProcessTraitJob(
    val metadataId: UUID,
    val version: Int,
    val traitId: String
) : IJobDefinition

@JobDefinition(ProcessTraitJob::class, JobQueueNames.contentJobQueue, "process-trait")
class ProcessTraitExecutor : AbstractJobExecutor<ProcessTraitJob>(ProcessTraitJob.serializer()) {

    override suspend fun execute() {
        val definition = getJobDefinition()
        TODO()
    }
}