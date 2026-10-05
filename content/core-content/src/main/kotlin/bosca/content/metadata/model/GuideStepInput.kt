package bosca.content.metadata.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class GuideStepInput(
    @Contextual
    val stepMetadataId: UUID? = null,
    val stepMetadataVersion: Int? = null,
    val metadata: MetadataInput? = null,
    val modules: List<GuideStepModuleInput>,
)