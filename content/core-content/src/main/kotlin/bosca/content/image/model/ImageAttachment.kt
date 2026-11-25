package bosca.content.image.model

import kotlinx.serialization.Serializable

/** Attachment outcome, including template choices and any incomplete post-processing. */
@Serializable
data class ImageAttachment(
    val success: Boolean,
    val attached: Boolean = false,
    val targetLanguageTag: String? = null,
    val attribute: TemplateImageAttribute? = null,
    val availableAttributes: List<TemplateImageAttribute> = emptyList(),
    val crop: Coordinates? = null,
    val collaborationUpdated: Boolean = false,
    val optimized: Boolean = false,
    val warning: String? = null,
    val error: String? = null,
)
