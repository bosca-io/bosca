package bosca.ai.kit.harness

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** The caller's scope and group intersection retained for a runtime-owned scoped credential. */
@Serializable
data class KitRuntimeAuthority(
    val scopes: List<String>,
    val groups: List<@Contextual UUID>,
    /** Original caller scope ceiling for policy authorization; null denotes a browser/session identity. */
    val callerScopes: List<String>? = null,
)
