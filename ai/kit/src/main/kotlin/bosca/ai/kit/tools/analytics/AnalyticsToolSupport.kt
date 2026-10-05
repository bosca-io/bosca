package bosca.ai.kit.tools.analytics

import bosca.analytics.model.AnalyticsQueryParameterInput
import bosca.analytics.model.QueryParameterType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Model-visible analytics parameter declaration shared by query and dashboard tools. */
@Serializable
data class AnalyticsParameterInput(
    val parameter: String,
    val name: String,
    val description: String = "",
    val type: QueryParameterType,
    val arrayType: QueryParameterType? = null,
    val defaultValue: JsonElement? = null,
    val required: Boolean = false,
) {
    fun toModel() = AnalyticsQueryParameterInput(
        parameter = parameter,
        name = name,
        description = description,
        type = type,
        arrayType = arrayType,
        defaultValue = defaultValue,
        required = required,
    )
}

/** Turn a display name into a stable lowercase analytics key. */
internal fun analyticsKey(name: String): String = name
    .lowercase()
    .replace(NON_ALPHANUMERIC, "-")
    .trim('-')
    .ifBlank { "analytics" }

/** Resolve a unique key, appending `-2`, `-3`, … when the base is already present. */
internal suspend fun uniqueAnalyticsKey(name: String, exists: suspend (String) -> Boolean): String {
    val base = analyticsKey(name)
    if (!exists(base)) return base
    var suffix = 2
    while (exists("$base-$suffix")) suffix++
    return "$base-$suffix"
}

/** Preserve coroutine cancellation while converting ordinary service failures to tool envelopes. */
internal suspend fun <T> analyticsCall(block: suspend () -> T): AnalyticsCallResult<T> = try {
    AnalyticsCallResult(value = block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    AnalyticsCallResult(error = e.message ?: e::class.simpleName ?: "Unknown analytics error")
}

internal data class AnalyticsCallResult<T>(val value: T? = null, val error: String? = null)

private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
