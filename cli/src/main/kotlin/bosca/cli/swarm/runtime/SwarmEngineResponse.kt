package bosca.cli.swarm.runtime

import kotlinx.serialization.json.JsonElement

internal data class SwarmEngineResponse(val status: Int, val body: JsonElement)
