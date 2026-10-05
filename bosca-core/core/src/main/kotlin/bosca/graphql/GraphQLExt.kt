package bosca.graphql

import bosca.serialization.JsonConverter.toJsonElement
import bosca.server.BoscaApplication
import bosca.graphql.server.ResolverContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

typealias DataFetchingEnvironment = ResolverContext

inline fun <reified T> DataFetchingEnvironment.getArgumentObject(name: String, json: Json): T {
    return json.decodeFromJsonElement<T>(getArgument<Map<String, Any>>(name).toJsonElement())
}

val DataFetchingEnvironment.application: BoscaApplication
    get() = context.getAs<BoscaApplication>("application") ?: error("Missing application argument")
