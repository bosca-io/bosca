package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.graphql.execute
import bosca.bml.render.currentRenderContext
import bosca.server.http.await
import bosca.workops.ui.generated.WorkOpsFormChoices
import bosca.workops.ui.graphql.FormSchemaType
import bosca.workops.ui.graphql.WorkOpsProfileChoices
import bosca.workops.ui.graphql.WorkOpsTaskCreateForm
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Resolves only the selected stored form's configuration under the current viewer's credentials. */
class WorkOpsFormChoicesLookup(graphqlEndpoint: String) : WorkOpsFormChoices {
    private val api = graphqlEndpoint.substringBefore("/graphql").trimEnd('/').toHttpUrl()
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        // API scripts retain their existing thirty-second execution budget.
        .readTimeout(40, TimeUnit.SECONDS).build()

    override suspend fun choices(gql: GraphQLClient, key: String, version: Int, fieldKey: String, query: String?, offset: Int): Map<String, String> {
        require(offset >= 0) { "Offset must be non-negative." }
        val form = gql.execute(WorkOpsTaskCreateForm, WorkOpsTaskCreateForm.Variables(key)).formSchemas.byKey
            ?: return mapOf("error" to "The task form is unavailable. Reload the form.")
        if (form.type != FormSchemaType.WORK_OPS || form.version != version) {
            return mapOf("error" to "The task form changed. Reload the form.")
        }
        fun find(layout: JsonArray, parent: String): JsonObject? {
            for ((index, item) in layout.withIndex()) {
                val node = item.jsonObject
                val nodeKey = "$parent-$index"
                if (nodeKey == fieldKey && node["type"] == JsonPrimitive("field")) return node
                find(node["children"] as? JsonArray ?: JsonArray(emptyList()), nodeKey)?.let { return it }
            }
            return null
        }
        val node = requireNotNull(find(form.uiSchema.jsonObject.getValue("layout").jsonArray, "form")) { "The form field is unavailable." }
        val control = node.getValue("control").jsonPrimitive.content
        if (control == "profile-select") {
            val documents = gql.execute(WorkOpsProfileChoices, WorkOpsProfileChoices.Variables(query.orEmpty(), offset))
                .search.search?.documents.orEmpty()
            val options = documents.take(20).mapNotNull { it.profile }.distinctBy { it.id }
                .map { buildJsonObject { put("value", it.id); put("label", it.name) } }
            return page(options, offset, 20, documents.size > 20)
        }
        val configuration = when (control) {
            "graphql-select" -> node["graphql"] as? JsonObject
            "api-script-select" -> node["apiScript"] as? JsonObject
            else -> error("This field does not have remote choices.")
        } ?: error("The form field is missing its choice configuration.")
        val search = configuration[if (control == "graphql-select") "searchVariable" else "searchParam"]?.jsonPrimitive?.contentOrNull
        if (query == null && !search.isNullOrEmpty()) return page(emptyList(), 0, 25, false)
        val result = if (control == "graphql-select") {
            val variables = (configuration["variables"] as? JsonObject).orEmpty().toMutableMap()
            if (!search.isNullOrEmpty() && query != null) variables[search] = JsonPrimitive(query)
            gql.execute(configuration.getValue("query").jsonPrimitive.content, JsonObject(variables))
        } else {
            val params = (configuration["params"] as? JsonObject).orEmpty().toMutableMap()
            if (!search.isNullOrEmpty() && query != null) params[search] = JsonPrimitive(query)
            executeScript(configuration, JsonObject(params))
        }
        val resultPath = configuration["resultPath"]?.jsonPrimitive?.contentOrNull
        val items = (if (resultPath.isNullOrEmpty() && control == "api-script-select") result else formChoicePath(result, resultPath.orEmpty())) as? JsonArray
            ?: error("Could not resolve form choices at ${resultPath.orEmpty().ifBlank { "the response root" }}.")
        val valuePath = configuration.getValue("valuePath").jsonPrimitive.content
        val labelPath = configuration["labelPath"]?.jsonPrimitive?.contentOrNull ?: valuePath
        val options = items.map { item -> buildJsonObject {
            put("value", formChoiceString(formChoicePath(item, valuePath)?.takeUnless { it == JsonNull } ?: JsonPrimitive("")))
            put("label", formChoiceString(formChoicePath(item, labelPath)?.takeUnless { it == JsonNull } ?: JsonPrimitive("")))
        } }
        if (search.isNullOrEmpty()) return mapOf("options" to JsonArray(options).toString())
        return page(options.drop(offset).take(25), offset, 25, options.size > offset + 25)
    }

    private suspend fun executeScript(configuration: JsonObject, params: JsonObject): JsonElement {
        val url = api.newBuilder().addPathSegments("api/v1/s").addPathSegment(configuration.getValue("key").jsonPrimitive.content)
        val method = configuration["method"]?.jsonPrimitive?.contentOrNull ?: "GET"
        require(method == "GET" || method == "POST") { "Unsupported API-script method: $method" }
        val request = Request.Builder().header("Accept", "application/json")
        currentRenderContext().token?.let { token -> request.header("Authorization", if (token.startsWith("Bearer ")) token else "Bearer $token") }
        if (method == "GET") {
            params.forEach { (key, value) -> url.addQueryParameter(key, formChoiceString(value)) }
            request.get()
        } else request.post(params.toString().toRequestBody("application/json".toMediaType()))
        client.newCall(request.url(url.build()).build()).await().use { response ->
            check(response.isSuccessful) { "API-script choices failed (${response.code})." }
            return Json.parseToJsonElement(response.body.string())
        }
    }

    private fun page(options: List<JsonObject>, offset: Int, size: Int, more: Boolean) = buildMap {
        put("options", JsonArray(options).toString())
        if (offset > 0) put("previousOffset", (offset - size).coerceAtLeast(0).toString())
        if (more) put("nextOffset", (offset + size).toString())
    }
}

internal fun formChoicePath(value: JsonElement, path: String): JsonElement? = path.split('.').fold(value as JsonElement?) { current, key ->
    when (current) {
        is JsonObject -> current[key]
        is JsonArray -> key.toIntOrNull()?.let { current.getOrNull(it) }
        else -> null
    }
}

/** Matches the Forms client's String(value) conversion for configured GET parameters and labels. */
internal fun formChoiceString(value: JsonElement): String = when (value) {
    JsonNull -> "null"
    is JsonPrimitive -> if (value.isString || value.booleanOrNull != null) value.content else formChoiceNumber(value)
    is JsonArray -> value.joinToString(",") { if (it == JsonNull) "" else formChoiceString(it) }
    else -> "[object Object]"
}

private fun formChoiceNumber(value: JsonPrimitive): String {
    val number = value.doubleOrNull ?: return value.content
    if (number == 0.0) return "0"
    if (!number.isFinite()) return number.toString()
    if (abs(number) >= 1e-6 && abs(number) < 1e21) return BigDecimal.valueOf(number).stripTrailingZeros().toPlainString()
    val parts = number.toString().lowercase().split('e')
    if (parts.size != 2) return parts[0].removeSuffix(".0")
    val exponent = parts[1].toInt()
    return parts[0].removeSuffix(".0") + "e" + (if (exponent >= 0) "+" else "") + exponent
}
