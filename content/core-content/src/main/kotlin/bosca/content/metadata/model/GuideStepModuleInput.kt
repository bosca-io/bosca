package bosca.content.metadata.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class GuideStepModuleInput(
    val metadata: MetadataInput? = null,
    @Contextual
    val moduleMetadataId: UUID? = null,
    val moduleMetadataVersion: Int? = null,
)