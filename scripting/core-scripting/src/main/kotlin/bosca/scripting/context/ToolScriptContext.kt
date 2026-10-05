package bosca.scripting.context

import bosca.security.service.AuthenticationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class ToolScriptContext(
    authentication: AuthenticationContext,
    scope: CoroutineScope,
    input: JsonObject,
    json: Json
) : BoscaScriptContext(authentication, scope, input, json)
