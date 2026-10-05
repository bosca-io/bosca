package bosca.scripting.service

import bosca.scripting.context.ScriptContext
import bosca.scripting.engine.Engine
import bosca.scripting.engine.KtsEngine
import bosca.scripting.model.Script
import bosca.serialization.JsonConverter.toJsonElement
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

class LocalScriptExecutionServiceImpl(private val engine: Engine) : ScriptExecutionService {

    override val isRemoteEnvironment = false

    override suspend fun <T> execute(script: Script, context: ScriptContext, deserializer: DeserializationStrategy<T>?): T? {
        val compiled = engine.compile<T>(script.key, script.version, script.source)
        return compiled.execute(context)
    }

    @OptIn(InternalSerializationApi::class)
    override suspend fun executeAsJson(script: Script, context: ScriptContext): JsonElement {
        return when (val returnValue = execute<Any>(script, context)) {
            is JsonObject -> returnValue
            null -> JsonNull
            else -> returnValue.toJsonElement()
        }
    }

    override fun invalidateCache(key: String, version: Int) {
        engine.invalidate(key, version)
    }

    override fun invalidateAllCaches() {
        engine.invalidateAll()
    }
}
