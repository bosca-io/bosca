package bosca.scripting.context

import bosca.security.service.AuthenticationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

open class TriggerContext(
    authentication: AuthenticationContext,
    scope: CoroutineScope,
    json: Json,
    val eventName: String,
    val eventPayload: JsonElement
) : BoscaScriptContext(authentication, scope, buildJsonObject {
    put("eventName", JsonPrimitive(eventName))
    put("eventPayload", eventPayload)
}, json)
