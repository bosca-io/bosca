package bosca.segmentation.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The input handed to a pipeline run for a single segment member when a segment's
 * audience is fanned out over a pipeline (one durable run per profile).
 *
 * A pipeline intended to process a segment declares a JSON input node shaped like
 * this object, so each run receives both the originating [segmentId] and the
 * [profileId] of the member it is running for. [context] carries the fan-out node's optional
 * inbound value so callers can provide campaign, template, or other run-specific information
 * without colliding with the profile identity fields.
 */
@Serializable
data class SegmentPipelineInput(
    @Contextual
    val segmentId: UUID,
    @Contextual
    val profileId: UUID,
    val context: JsonElement? = null,
)
