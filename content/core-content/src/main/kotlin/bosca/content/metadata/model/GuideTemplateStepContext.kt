package bosca.content.metadata.model

import bosca.serialization.ZonedDateTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
class GuideTemplateStepContext(
    val guide: Guide,
    val step: GuideTemplateStep,
    @Contextual
    val date: ZonedDateTime?
)