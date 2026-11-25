package bosca.cli.swarm.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Native Swarm ServiceSpec plus references to existing provider/MCP Docker Secrets. */
@Serializable
data class SwarmRuntimeProfile(val service: JsonObject, val secretEnvironment: Map<String, String> = emptyMap(),
    /** Manager-local Docker Secret filename containing the image registry's authentication payload. */
    val registryAuthSecret: String? = null)
