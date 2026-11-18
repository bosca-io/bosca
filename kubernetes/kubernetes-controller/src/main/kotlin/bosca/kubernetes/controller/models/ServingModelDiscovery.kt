package bosca.kubernetes.controller.models

import bosca.server.http.await
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Reads authoritative selection and reports serving lifecycle changes through the same GraphQL API. */
class ServingModelDiscovery(private val http: OkHttpClient, private val json: Json) {
    /** Queues activation after the controller claims the ServingModel for this selection revision. */
    suspend fun prepareModel(profile: ServingModelProfile, token: String, version: Long, selectionRevision: Long, endpoint: String): Boolean =
        request(profile, token, PREPARE_QUERY, mapOf(
            "version" to JsonPrimitive(version), "selectionRevision" to JsonPrimitive(selectionRevision), "endpoint" to JsonPrimitive(endpoint),
        )).getValue("modelServingPrepared").jsonPrimitive.boolean

    /** Reports terminal failures with a separate write-authorized credential; rejected reports preserve resources. */
    suspend fun failModel(profile: ServingModelProfile, token: String, version: Long, selectionRevision: Long, failure: String): Boolean =
        request(profile, token, FAILURE_QUERY, mapOf(
            "version" to JsonPrimitive(version), "selectionRevision" to JsonPrimitive(selectionRevision), "failure" to JsonPrimitive(failure),
        )).getValue("modelServingFailed").jsonPrimitive.boolean

    suspend fun discover(profile: ServingModelProfile, token: String): Pair<List<ServingModelContext>, List<ServingModelExport>> {
        val contexts = request(profile, token, QUERY)
        return json.decodeFromJsonElement(ListSerializer(ServingModelContext.serializer()), contexts.getValue("all")) to
            json.decodeFromJsonElement(ListSerializer(ServingModelExport.serializer()), contexts.getValue("servingModels"))
    }

    private suspend fun request(profile: ServingModelProfile, token: String, query: String, variables: Map<String, JsonPrimitive> = emptyMap()): JsonObject {
        val body = JsonObject(mapOf("query" to JsonPrimitive(query), "variables" to JsonObject(variables)))
        val request = Request.Builder().url("${profile.boscaUrl.trimEnd('/')}/graphql").header("Authorization", "Bearer $token")
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody("application/json".toMediaType())).build()
        return http.newCall(request).await().use { response ->
            check(response.isSuccessful) { "Serving model request returned HTTP ${response.code}" }
            val result = json.parseToJsonElement(response.body.string()).jsonObject
            check(result["errors"] == null) { "Serving model request returned GraphQL errors" }
            result.getValue("data").jsonObject.getValue("recommendation").jsonObject.getValue("contexts").jsonObject
        }
    }

    companion object {
        private const val PREPARE_QUERY = "mutation ServingPrepared(\$version: Long!, \$selectionRevision: Long!, \$endpoint: String!) { recommendation { contexts { modelServingPrepared(version: \$version, selectionRevision: \$selectionRevision, endpoint: \$endpoint) } } }"
        private const val FAILURE_QUERY = "mutation ServingFailure(\$version: Long!, \$selectionRevision: Long!, \$failure: String!) { recommendation { contexts { modelServingFailed(version: \$version, selectionRevision: \$selectionRevision, failure: \$failure) } } }"
        private const val QUERY = """query ServingModelDiscovery { recommendation { contexts {
            all { id selectionRevision activeModelVersion requestedModelVersion }
            servingModels { contextId version selectionRevision status contentModelName personalizedModelName personalized }
        } } }"""
    }
}
