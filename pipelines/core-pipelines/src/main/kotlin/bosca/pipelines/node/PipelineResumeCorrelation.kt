@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.node

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi

/**
 * The run/node identity a suspendable node stamps onto the out-of-band work it enqueues — set as the
 * backing job's `context` — so the completion of that work can be routed back to the right parked run.
 * The run job's drive listener reads it off the terminal child job and resumes the
 * run keyed by [runId]/[nodeId].
 */
@Serializable
data class PipelineResumeCorrelation(
    @Contextual
    val runId: UUID,
    val nodeId: String,
)