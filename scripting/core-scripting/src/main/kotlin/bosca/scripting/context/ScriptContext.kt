package bosca.scripting.context

import bosca.security.service.AuthenticationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

/**
 * Provides the execution environment for scripts, carrying coroutine scope, authentication
 * state, and typed access to input data.
 *
 * This is a sealed hierarchy whose concrete implementations supply context for different
 * script invocation scenarios (e.g., general execution via [bosca.scripting.context.DefaultScriptContext],
 * event-triggered execution via [bosca.scripting.context.TriggerContext], or tool invocation via
 * [bosca.scripting.context.ToolScriptContext]).
 */
sealed interface ScriptContext {

    /**
     * The coroutine scope under which the script executes, allowing scripts to launch
     * structured child coroutines.
     */
    val scope: CoroutineScope

    /**
     * The authentication context of the caller that initiated the script execution,
     * providing access to the current user's identity and permissions.
     */
    val authentication: AuthenticationContext

    /**
     * The [Json] instance used for serialization and deserialization of context values,
     * including the input data supplied to the script.
     */
    val json: Json

    /**
     * Retrieves a typed value from the script's input data by key, using the KClass to
     * resolve the appropriate serializer.
     *
     * @param T the expected type of the value
     * @param type the KClass of the target type, used to look up the serializer
     * @param key the key identifying the value within the input data
     * @return the deserialized value, or `null` if the key is absent or the input is empty
     */
    fun <T : Any> get(type: KClass<T>, key: String): T?

    /**
     * Retrieves a typed value from the script's input data by key, using an explicit
     * [KSerializer] for deserialization.
     *
     * @param T the expected type of the value
     * @param serializer the serializer to use for decoding the value
     * @param key the key identifying the value within the input data
     * @return the deserialized value, or `null` if the key is absent or the input is empty
     */
    fun <T : Any> get(serializer: KSerializer<T>, key: String): T?
}

inline fun <reified T : Any> ScriptContext.get(key: String): T? = get(T::class, key)

class ContextValueDelegate<T : Any>(
    private val context: ScriptContext,
    private val key: String?
) {

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
        val actualKey = key ?: property.name
        @Suppress("UNCHECKED_CAST")
        val type = context.json.serializersModule.serializer(property.returnType) as KSerializer<T>
        return context.get(type, actualKey) ?: error("No value found for '$actualKey'")
    }
}

inline fun <reified T : Any> ScriptContext.value(key: String? = null): ContextValueDelegate<T> {
    return ContextValueDelegate(this, key)
}