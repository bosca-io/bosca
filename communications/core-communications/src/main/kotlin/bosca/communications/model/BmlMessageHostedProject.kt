package bosca.communications.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A project the BML Message Server currently hosts, as the authoring surfaces see it:
 * the active (latest published) version, the registry's [pinnedVersion] when one is set (what
 * sends actually render), and the active version's template keys. Includes projects that were
 * never registered — registration is optional and only contributes the pin.
 */
@Serializable
data class BmlMessageHostedProject(
    val project: String,
    val activeVersion: String,
    val pinnedVersion: String? = null,
    val templates: List<BmlMessageTemplateInfo>,
)

/**
 * One template of a hosted project. [samplePayload] is the payload skeleton derived from the
 * template's declared payload serializer — the Studio preview pre-fills it so authors see the
 * structure; null when the template takes no payload. [payloadSchema] is the same serializer
 * projected as a JSON-Schema subset (`type`/`properties`/`required`/`items`/`enum`) — the
 * machine-readable contract authoring surfaces (e.g. the pipeline node inspector) render.
 */
@Serializable
data class BmlMessageTemplateInfo(
    val key: String,
    @Contextual val samplePayload: JsonElement? = null,
    @Contextual val payloadSchema: JsonElement? = null,
    /** True when this BML message unit declares email output. */
    val supportsEmail: Boolean = true,
    /** True when this BML message unit declares push output. */
    val supportsPush: Boolean = false,
)
