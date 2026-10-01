package bosca.scripting.service

import bosca.scripting.context.ScriptContext
import bosca.scripting.model.Script
import bosca.service.Service
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer

/**
 * Service for compiling and executing scripts within a given [ScriptContext].
 *
 * Scripts are compiled from their source code (identified by key and version) and may be
 * cached by the underlying engine. This interface also provides cache invalidation
 * operations to force recompilation when script source changes.
 */
interface ScriptExecutionService : Service {

    val isRemoteEnvironment: Boolean

    /**
     * Compiles and executes a script, returning the result deserialized to the expected type.
     *
     * The [deserializer] carries the type information needed by remote execution environments
     * to properly deserialize the JSON result. Local implementations may ignore it and return
     * the script's result directly.
     *
     * @param T the expected return type of the script execution
     * @param script the script definition containing the source code, key, and version
     * @param context the execution context providing input data, authentication, and coroutine scope
     * @param deserializer the strategy for deserializing the result to [T]
     * @return the script's return value as [T], or `null` if the script produces no result
     */
    suspend fun <T> execute(script: Script, context: ScriptContext, deserializer: DeserializationStrategy<T>? = null): T?

    /**
     * Compiles and executes a script, converting the result to a [JsonElement].
     *
     * If the script returns a [kotlinx.serialization.json.JsonObject], it is returned directly.
     * A `null` result is returned as [kotlinx.serialization.json.JsonNull]. All other return
     * values are serialized to their JSON representation.
     *
     * @param script the script definition containing the source code, key, and version
     * @param context the execution context providing input data, authentication, and coroutine scope
     * @return the script's return value as a [JsonElement]
     */
    suspend fun executeAsJson(script: Script, context: ScriptContext): JsonElement

    /**
     * Invalidates the cached compilation for a specific script identified by its key and version.
     *
     * After invalidation, the next call to [execute] or [executeAsJson] for this script will
     * trigger a fresh compilation from source.
     *
     * @param key the unique key identifying the script
     * @param version the version number of the cached compilation to invalidate
     */
    fun invalidateCache(key: String, version: Int)

    /**
     * Invalidates all cached script compilations, forcing recompilation on next execution.
     *
     * This is useful when a bulk update has occurred or when the engine state needs to be reset.
     */
    fun invalidateAllCaches()
}

/**
 * Compiles and executes a script, resolving the serializer for [T] at the call site via `reified`.
 *
 * This is the preferred entry point for typed script execution as it works correctly in both
 * local and remote environments without requiring callers to manually construct a serializer.
 *
 * @param T the expected return type (must be `@Serializable`)
 * @param script the script definition containing the source code, key, and version
 * @param context the execution context providing input data, authentication, and coroutine scope
 * @return the deserialized script result, or `null` if the script produces no result
 */
suspend inline fun <reified T> ScriptExecutionService.execute(
    script: Script,
    context: ScriptContext,
): T? = execute(script, context, serializer<T>())
