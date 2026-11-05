package bosca.cli.swarm.runtime

import kotlinx.serialization.json.JsonElement

/** Manager-only Docker Engine API; JSON is the native Docker schema. */
internal interface SwarmEngine {
    /** Performs one bounded request without placing its body or sensitive headers in process arguments or logs. */
    suspend fun request(method: String, path: String, body: JsonElement? = null, headers: Map<String, String> = emptyMap()): SwarmEngineResponse
}
