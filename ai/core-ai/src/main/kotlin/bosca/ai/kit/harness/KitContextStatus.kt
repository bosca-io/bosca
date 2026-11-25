package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** Current permitted sources and handoff limitations, without bodies or connection credentials. */
@Serializable
data class KitContextStatus(
    val sources: List<KitContextReference>,
    val integrations: List<String>,
    val notices: List<String>,
    val selectionCount: Int = 0,
)
