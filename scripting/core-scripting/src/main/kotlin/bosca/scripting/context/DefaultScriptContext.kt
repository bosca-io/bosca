package bosca.scripting.context

import bosca.security.service.AuthenticationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

class DefaultScriptContext(
    authentication: AuthenticationContext,
    scope: CoroutineScope,
    input: JsonElement = JsonNull,
    json: Json
) : BoscaScriptContext(authentication, scope, input, json)
