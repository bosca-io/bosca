package bosca.git.model

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/**
 * Job payload for evaluating pipeline triggers after a push. Reads
 * pipeline YAML files from the repository at the pushed commit,
 * syncs pipeline definitions, and creates pipeline runs for any
 * matching triggers.
 */
@Serializable
data class PipelineTriggerJob(
    val repositoryId: UUID,
    val ref: String,
    val beforeSha: String,
    val afterSha: String,
    /** Initiating principal ID. A missing, deleted, or unauthorized principal cannot start CI. */
    val pusherPrincipalId: UUID? = null
) : IJobDefinition
