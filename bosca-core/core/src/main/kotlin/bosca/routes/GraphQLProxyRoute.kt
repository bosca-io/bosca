package bosca.routes

import bosca.graphql.GraphQLService
import bosca.security.service.AuthenticationContext
import bosca.serialization.JsonContent
import bosca.server.ServerCall
import io.opentelemetry.api.trace.Tracer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory

abstract class GraphQLProxyRoute(
    private val graphQLService: GraphQLService,
    private val tracer: Tracer,
    private val json: Json,
) : Route<JsonContent<JsonElement>>() {

    abstract suspend fun getOperationName(call: ServerCall, authenticationContext: AuthenticationContext): String

    abstract suspend fun getQuery(call: ServerCall, authenticationContext: AuthenticationContext): String

    open suspend fun getVariables(call: ServerCall, authenticationContext: AuthenticationContext): JsonObject? = null

    abstract val responsePath: List<String>

    open suspend fun transform(element: JsonElement): JsonElement? {
        var response: JsonElement? = element
        responsePath.forEach {
            response = response?.jsonObject[it]
        }
        return response
    }

    override fun serializer(): KSerializer<JsonContent<JsonElement>>? = null

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): JsonContent<JsonElement> {
        val response = graphQLService.getAsJsonElement(
            call,
            getOperationName(call, authenticationContext),
            getQuery(call, authenticationContext),
            getVariables(call, authenticationContext),
            null
        )
        return try {
            if (response.jsonObject.containsKey("errors")) {
                return JsonContent(tracer, json, response, JsonElement.serializer())
            } else {
                val data = transform(response.jsonObject["data"] ?: JsonNull) ?: JsonNull
                JsonContent(tracer, json, data, JsonElement.serializer())
            }
        } catch (e: Exception) {
            log.error("Error transforming GraphQL response", e)
            JsonContent(tracer, json, response, JsonElement.serializer())
        }
    }

    companion object {

        private val log = LoggerFactory.getLogger(GraphQLProxyRoute::class.java)
    }
}
