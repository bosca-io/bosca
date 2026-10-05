package bosca.ai.kit.tools.pipeline

import bosca.ai.kit.agents.pipeline.PipelineServices
import bosca.graphql.GraphQLRequest
import bosca.security.service.AuthenticationContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal suspend fun PipelineServices.executeGraphQL(
    authentication: AuthenticationContext,
    query: String,
    variables: JsonObject? = null,
): JsonElement = graphQLService.execute(authentication, GraphQLRequest(query = query, variables = variables))

/** Return a caller-readable GraphQL error without hiding resolver failures from the agent. */
internal fun JsonElement.graphQLError(): String? {
    val errors = (this as? JsonObject)?.get("errors") as? JsonArray ?: return null
    if (errors.isEmpty()) return null
    return errors.joinToString("; ") { error ->
        ((error as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull ?: error.toString()
    }
}

internal fun JsonElement.graphQLData(vararg path: String): JsonElement? {
    var current: JsonElement? = (this as? JsonObject)?.get("data")
    for (segment in path) current = (current as? JsonObject)?.get(segment) ?: return null
    return current
}

internal fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
internal fun JsonObject.optionalString(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull
internal fun JsonObject.boolean(name: String): Boolean = getValue(name).jsonPrimitive.content.toBooleanStrict()
internal fun JsonObject.long(name: String): Long = getValue(name).jsonPrimitive.content.toLong()
