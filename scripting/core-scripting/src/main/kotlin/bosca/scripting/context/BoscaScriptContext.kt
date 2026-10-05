package bosca.scripting.context

import bosca.security.service.AuthenticationContext
import bosca.serialization.resolveSerializerOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.reflect.KClass

sealed class BoscaScriptContext(
    override val authentication: AuthenticationContext,
    override val scope: CoroutineScope,
    val input: JsonElement = JsonNull,
    override val json: Json
) : ScriptContext {

    override fun <T : Any> get(type: KClass<T>, key: String): T? {
        val serializer = resolveSerializerOrNull(type) ?: return null
        return get(serializer, key)
    }

    override fun <T : Any> get(serializer: KSerializer<T>, key: String): T? {
        if (input == JsonNull) return null
        if (input !is JsonObject) return null
        val result = input.jsonObject[key] ?: return null
        return json.decodeFromJsonElement(serializer, result)
    }
}
