package bosca.cli.analytics

import bosca.graphql.gen.AnalyticsQueryExecutionParameterInput
import bosca.graphql.gen.AnalyticsQueryParameterInput
import bosca.graphql.gen.AnalyticsVisualizationInstanceInput
import bosca.graphql.gen.IAnalyticsDashboardFragment
import bosca.graphql.gen.IAnalyticsDashboardSummaryFragment
import bosca.graphql.gen.IAnalyticsQueryFragment
import bosca.graphql.gen.IAnalyticsQuerySummaryFragment
import bosca.graphql.gen.IAnalyticsVisualizationFragment
import bosca.graphql.gen.IAnalyticsVisualizationSummaryFragment
import bosca.graphql.gen.QueryParameterType
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

internal val analyticsJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

internal val compactAnalyticsJson = Json {
    prettyPrint = false
}

internal fun parseJsonArgument(value: String, label: String): JsonElement {
    val source = if (value.startsWith("@")) {
        val path = value.drop(1)
        require(path.isNotBlank()) { "$label uses @file syntax but no file was provided" }
        File(path).readText()
    } else {
        value
    }
    return try {
        Json.parseToJsonElement(source)
    } catch (e: IllegalArgumentException) {
        error("Invalid $label JSON: ${e.message}")
    }
}

internal fun parseJsonObjectArgument(value: String?, label: String): JsonObject? =
    value?.let {
        parseJsonArgument(it, label) as? JsonObject
            ?: error("$label must be a JSON object")
    }

internal fun parseExecutionParameters(values: List<String>): List<AnalyticsQueryExecutionParameterInput> =
    values.map { value ->
        val separator = value.indexOf('=')
        require(separator > 0) { "Query parameters must use name=value: $value" }
        val name = value.substring(0, separator)
        val raw = value.substring(separator + 1)
        val parsed = try {
            Json.parseToJsonElement(raw)
        } catch (_: IllegalArgumentException) {
            JsonPrimitive(raw)
        }
        AnalyticsQueryExecutionParameterInput(parameter = name, value = parsed)
    }

internal fun JsonObject.toQueryParameterInput(): AnalyticsQueryParameterInput =
    AnalyticsQueryParameterInput(
        parameter = requiredString("parameter"),
        name = optionalString("name") ?: requiredString("parameter"),
        description = optionalString("description").orEmpty(),
        type = enumValue<QueryParameterType>(requiredString("type")),
        arrayType = optionalString("arrayType")?.let(::enumValue),
        defaultValue = this["defaultValue"]?.takeUnless { it is JsonNull },
        required = this["required"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
    )

internal fun JsonObject.toVisualizationInstanceInput(): AnalyticsVisualizationInstanceInput =
    AnalyticsVisualizationInstanceInput(
        visualizationId = Uuid.parse(requiredString("visualizationId")),
        configuration = this["configuration"]?.takeUnless { it is JsonNull },
    )

internal inline fun <reified T : Enum<T>> enumValue(value: String): T =
    enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: error("Unknown ${T::class.simpleName}: $value")

internal fun JsonObject.requiredString(key: String): String =
    optionalString(key) ?: error("Missing required field: $key")

internal fun JsonObject.optionalString(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

internal fun IAnalyticsQuerySummaryFragment.toJson(): JsonObject = buildJsonObject {
    put("id", id.toString())
    put("key", key)
    put("name", name)
    put("description", description)
    put("refreshIntervalSeconds", refreshIntervalSeconds)
    put("parameters", buildJsonArray {
        parameters.sortedBy { it.sort }.forEach { parameter ->
            add(buildJsonObject {
                put("parameter", parameter.parameter)
                put("name", parameter.name)
                put("description", parameter.description)
                put("type", parameter.type.name)
                put("arrayType", parameter.arrayType.name)
                put("defaultValue", parameter.defaultValue ?: JsonNull)
                put("required", parameter.required)
                put("sort", parameter.sort)
            })
        }
    })
    put("columns", buildJsonArray {
        columns.forEach { column ->
            add(buildJsonObject {
                put("name", column.name)
                put("typeName", column.typeName)
                put("nullable", column.nullable)
            })
        }
    })
}

internal fun IAnalyticsQueryFragment.toJson(): JsonObject = buildJsonObject {
    put("id", id.toString())
    put("key", key)
    put("name", name)
    put("description", description)
    put("refreshIntervalSeconds", refreshIntervalSeconds)
    put("parameters", buildJsonArray {
        parameters.sortedBy { it.sort }.forEach { parameter ->
            add(buildJsonObject {
                put("parameter", parameter.parameter)
                put("name", parameter.name)
                put("description", parameter.description)
                put("type", parameter.type.name)
                put("arrayType", parameter.arrayType.name)
                put("defaultValue", parameter.defaultValue ?: JsonNull)
                put("required", parameter.required)
                put("sort", parameter.sort)
            })
        }
    })
    put("columns", buildJsonArray {
        columns.forEach { column ->
            add(buildJsonObject {
                put("name", column.name)
                put("typeName", column.typeName)
                put("nullable", column.nullable)
            })
        }
    })
    put("query", query)
    put("configuration", configuration ?: JsonNull)
    put("permissions", buildJsonArray {
        permissions.forEach { permission ->
            add(buildJsonObject {
                put("action", permission.action.name)
                put("groupId", permission.groupId.toString())
                put("groupName", permission.group.name)
            })
        }
    })
}

internal fun IAnalyticsVisualizationSummaryFragment.toJson(): JsonObject = buildJsonObject {
    put("id", id.toString())
    put("key", key)
    put("name", name)
    put("description", description)
    put("queryId", queryId?.toString())
    put("type", type.name)
}

internal fun IAnalyticsVisualizationFragment.toJson(): JsonObject = buildJsonObject {
    put("id", id.toString())
    put("key", key)
    put("name", name)
    put("description", description)
    put("queryId", queryId?.toString())
    put("type", type.name)
    put("configuration", configuration ?: JsonNull)
    put("permissions", buildJsonArray {
        permissions.forEach { permission ->
            add(buildJsonObject {
                put("action", permission.action.name)
                put("groupId", permission.groupId.toString())
                put("groupName", permission.group.name)
            })
        }
    })
}

internal fun IAnalyticsDashboardSummaryFragment.toJson(): JsonObject = buildJsonObject {
    put("id", id.toString())
    put("key", key)
    put("name", name)
    put("description", description)
}

internal fun IAnalyticsDashboardFragment.toJson(): JsonObject = buildJsonObject {
    put("id", id.toString())
    put("key", key)
    put("name", name)
    put("description", description)
    put("configuration", configuration ?: JsonNull)
    put("parameters", buildJsonArray {
        parameters.orEmpty().forEach { parameter ->
            add(buildJsonObject {
                put("parameter", parameter.parameter)
                put("name", parameter.name)
                put("description", parameter.description)
                put("type", parameter.type.name)
                put("arrayType", parameter.arrayType?.name)
                put("defaultValue", parameter.defaultValue ?: JsonNull)
                put("required", parameter.required)
            })
        }
    })
    put("permissions", buildJsonArray {
        permissions.forEach { permission ->
            add(buildJsonObject {
                put("action", permission.action.name)
                put("groupId", permission.groupId.toString())
                put("groupName", permission.group.name)
            })
        }
    })
    put("visualizations", buildJsonArray {
        visualizations.forEach { instance ->
            add(buildJsonObject {
                put("instanceId", instance.id.toString())
                put("configuration", instance.configuration ?: JsonNull)
                put("visualization", instance.visualization.toJson())
            })
        }
    })
}

internal fun recordsJson(
    records: List<JsonElement>,
    cached: Boolean,
    refreshedAt: String?,
    maxRecords: Int = records.size,
): JsonObject {
    val bounded = records.take(maxRecords)
    return buildJsonObject {
        put("records", JsonArray(bounded))
        put("returnedRecords", bounded.size)
        put("truncated", records.size > bounded.size)
        put("cached", cached)
        put("refreshedAt", refreshedAt)
    }
}

internal fun tableLines(records: List<JsonElement>, maxRecords: Int = 100): List<String> {
    val rows = records.take(maxRecords).mapNotNull { it as? JsonObject }
    if (rows.isEmpty()) return listOf("No records.")
    val columns = rows.flatMap { it.keys }.distinct()
    val widths = columns.associateWith { column ->
        maxOf(
            column.length,
            rows.maxOfOrNull { row -> displayValue(row[column]).length } ?: 0,
        ).coerceAtMost(30)
    }
    fun formatRow(values: List<String>): String = values.mapIndexed { index, value ->
        value.take(widths.getValue(columns[index])).padEnd(widths.getValue(columns[index]))
    }.joinToString("  ")
    return buildList {
        add(formatRow(columns))
        add(columns.joinToString("  ") { "-".repeat(widths.getValue(it)) })
        rows.forEach { row -> add(formatRow(columns.map { displayValue(row[it]) })) }
        if (records.size > rows.size) add("… ${records.size - rows.size} more records")
    }
}

internal fun displayValue(value: JsonElement?): String = when (value) {
    null, JsonNull -> "null"
    is JsonPrimitive -> value.content
    else -> compactAnalyticsJson.encodeToString(JsonElement.serializer(), value)
}
