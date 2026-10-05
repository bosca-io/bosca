package bosca.pipelines.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Published on [CHANNEL] when a durable run parks on an **Approval Gate** — the signal that a person
 * must now decide (`Mutation.pipelines.resolveGate`). Notification surfaces subscribe (e.g. the workops
 * notification dispatcher turns it into inbox entries for the pipeline's EXECUTE-granted approvers);
 * the engine itself only announces the park, exactly like [PipelineTriggersChanged]'s plain-pub/sub
 * pattern — no jobs, no coupling to any notification product.
 */
@Serializable
data class PipelineAwaitingApproval(
    @Contextual val runId: UUID,
    @Contextual val pipelineId: UUID,
    /** The gate node the run is parked on — `resolveGate` needs it. */
    val nodeId: String,
    /** The gate's authored prompt (blank when the author didn't set one). */
    val prompt: String,
) {
    companion object {
        const val CHANNEL = "bosca.pipelines.run.awaiting-approval"
    }
}
