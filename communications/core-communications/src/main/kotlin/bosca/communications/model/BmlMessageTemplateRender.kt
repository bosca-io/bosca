package bosca.communications.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Audit details for the BML message-template render associated with a delivery.
 *
 * [version] is the concrete published artifact version returned by the renderer. [parameters]
 * retains the template payload supplied by the communication for successful and failed attempts.
 */
@Serializable
data class BmlMessageTemplateRender(
    val project: String,
    val templateKey: String,
    val version: String? = null,
    val parameters: JsonElement? = null,
)
