package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** A currently authorized source label for the conversation UI. */
@Serializable
data class KitContextReference(
    val kind: String, val id: String, val title: String,
    val ref: String? = null,
    val path: String? = null,
    val selected: Boolean = false,
)
