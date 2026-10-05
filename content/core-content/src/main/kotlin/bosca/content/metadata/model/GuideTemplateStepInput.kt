package bosca.content.metadata.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class GuideTemplateStepInput(
    val modules: List<GuideTemplateStepModuleInput>,
    @Contextual
    val templateMetadataId: UUID,
    val templateMetadataVersion: Int
)