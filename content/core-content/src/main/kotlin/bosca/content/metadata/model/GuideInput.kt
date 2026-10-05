package bosca.content.metadata.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class GuideInput(
    val guideType: GuideType,
    val rrule: String?,
    val steps: List<GuideStepInput>,
    @Contextual
    val templateMetadataId: UUID?,
    val templateMetadataVersion: Int?,
)