package bosca.ai.kit.harness

import bosca.ai.kit.harness.runtime.KitRuntimeSecrets
import kotlinx.serialization.Serializable

/** Immutable generation request sent only to the manager-side controller. */
@Serializable
data class KitSwarmRequest(val profile: String, val key: String, val image: String,
    val environment: Map<String, String>, val secrets: KitRuntimeSecrets)
