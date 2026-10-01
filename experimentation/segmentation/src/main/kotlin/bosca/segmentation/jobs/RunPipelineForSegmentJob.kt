package bosca.segmentation.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Job payload for starting one durable pipeline run per profile in a segment's audience.
 *
 * The fan-out runs under [principalId] — the principal that requested it — so each
 * started run executes end-to-end under that security context. [context] is an optional JSON value
 * copied into every `SegmentPipelineInput`; it is null for existing callers that do not provide
 * additional context.
 */
@Serializable
data class RunPipelineForSegmentJob(
    @Contextual
    val segmentId: UUID,
    @Contextual
    val pipelineId: UUID,
    @Contextual
    val principalId: UUID,
    val context: JsonElement? = null,
) : IJobDefinition
