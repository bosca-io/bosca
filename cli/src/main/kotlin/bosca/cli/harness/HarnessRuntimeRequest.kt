package bosca.cli.harness

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import io.ktor.websocket.WebSocketSession

internal data class HarnessRuntimeRequest(val originalId: JsonElement, val method: String, val turn: String?, val params: JsonObject,
    val owner: Long, val observer: WebSocketSession?)
