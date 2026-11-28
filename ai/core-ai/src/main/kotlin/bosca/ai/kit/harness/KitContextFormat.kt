package bosca.ai.kit.harness

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How Kit delivers source context to the harness. */
@Serializable
enum class KitContextFormat {
    @SerialName("text")
    TEXT,
    @SerialName("resource")
    RESOURCE,
}
