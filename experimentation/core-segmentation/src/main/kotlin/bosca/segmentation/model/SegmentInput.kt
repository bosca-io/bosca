package bosca.segmentation.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Input for creating or updating a segment definition, specifying the
 * segment's name, type, optional analytics query binding, and configuration.
 */
@Serializable
data class SegmentInput(
    val name: String,
    val description: String = "",
    val type: SegmentType,
    val status: SegmentStatus = SegmentStatus.DRAFT,
    @Contextual
    val analyticsQueryId: UUID? = null,
    @Contextual
    val configuration: JsonElement? = null,
    val evaluationSchedule: String? = null
)
