package bosca.content.metadata.model

import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class GuideTemplateStepModuleInput(
    val templateMetadataId: UUID,
    val templateMetadataVersion: Int
)