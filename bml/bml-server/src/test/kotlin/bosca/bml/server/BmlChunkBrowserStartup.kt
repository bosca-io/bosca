package bosca.bml.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Chromium can publish its DevTools port before creating the initial page target. */
internal fun awaitChunkBrowserPageTarget(
    deadline: Long,
    isAlive: () -> Boolean,
    readTargets: () -> List<JsonObject>,
): JsonObject? {
    while (isAlive() && System.nanoTime() < deadline) {
        readTargets().firstOrNull { it["type"]?.jsonPrimitive?.content == "page" }?.let { return it }
        Thread.sleep(25)
    }
    return null
}
