package bosca.cli.mcp

import bosca.graphql.client.GraphQLClient
import bosca.graphql.client.GraphQLJson
import bosca.graphql.client.GraphQLResponse
import bosca.graphql.language.OperationDefinition
import bosca.graphql.language.OperationType
import bosca.graphql.parser.Parser
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.*
import kotlin.coroutines.cancellation.CancellationException

/** Live schema discovery and operations use the same transport and identity as the domain tools. */
object GraphQLToolRegistrar {
    val tool = Tool(
        name = "bosca_graphql",
        description = "Discover the live Bosca GraphQL schema and execute a query or mutation as the current caller. " +
            "Use roots, search, then type to learn exact field names, arguments, wrappers and defaults before execute. " +
            "Schema discovery requires the server's introspection permission; it never uses a bundled or another caller's schema. " +
            "variables must be a JSON object. Select a named operation with operationName when the document contains several. " +
            "Results retain data and error messages, including partial data; errors are not successful completion. " +
            "Mutations and side-effecting queries require the user's authorization. Never automatically retry an uncertain operation; read back its outcome. " +
            "This tool does not grant scopes, bypass permissions or independently approve an operation. Use the domain tools when they fit the task.",
        inputSchema = ToolSchema(properties = buildJsonObject {
            putJsonObject("action") { put("type", "string"); put("enum", JsonArray(listOf("roots", "search", "type", "execute").map(::JsonPrimitive))) }
            for (key in listOf("query", "typeName", "operationName")) putJsonObject(key) { put("type", "string") }
            putJsonObject("variables") { put("type", "object") }
            putJsonObject("offset") { put("type", "integer"); put("minimum", 0) }
            putJsonObject("limit") { put("type", "integer"); put("minimum", 1); put("maximum", 100) }
        }, required = listOf("action")),
        annotations = ToolAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = true),
    )

    fun registerAll(server: Server, client: GraphQLClient) {
        server.addTool(tool) { request -> handle(client, request.arguments.orEmpty()) }
    }

    internal suspend fun handle(client: GraphQLClient, args: Map<String, JsonElement>): CallToolResult = try {
        val action = args.required("action")
        val response = when (action) {
            "roots" -> client.execute(ROOTS, null, "McpGraphQLRoots")
            "type" -> {
                val name = args.required("typeName")
                require(name.matches(NAME)) { "typeName must be a GraphQL name" }
                client.execute(TYPE, buildJsonObject { put("name", name) }, "McpGraphQLType")
            }
            "search" -> {
                val query = args.required("query")
                val offset = args["offset"]?.jsonPrimitive?.int ?: 0
                val limit = args["limit"]?.jsonPrimitive?.int ?: 20
                require(offset >= 0 && limit in 1..100) { "Use a nonnegative offset and a limit between 1 and 100" }
                val found = client.execute(SEARCH, null, "McpGraphQLSearch")
                if (!found.errors.isNullOrEmpty()) found else search(found, query, offset, limit)
            }
            "execute" -> {
                val query = args.required("query")
                val name = args["operationName"]?.let { value ->
                    require(value is JsonPrimitive && value.isString && value.content.matches(NAME)) { "operationName must be a GraphQL name" }
                    value.content
                }
                val operations = Parser.parse(query).definitions.filterIsInstance<OperationDefinition>()
                val operation = if (name == null) operations.singleOrNull() else operations.singleOrNull { it.name == name }
                require(operation != null) { "Select one operation; provide its operationName for a document with multiple operations" }
                require(operation.operation != OperationType.SUBSCRIPTION) { "Subscriptions need a streaming client; use a query or mutation" }
                val variables = args["variables"]?.let { value ->
                    require(value is JsonObject) { "variables must be a JSON object" }
                    value
                }
                client.execute(query, variables, name)
            }
            else -> error("Unknown action: $action")
        }
        check(response.data != null || !response.errors.isNullOrEmpty()) { "GraphQL response contained no data or errors" }
        val value = GraphQLJson.encodeToJsonElement(GraphQLResponse.serializer(), response).jsonObject
        val text = value.toString()
        CallToolResult(content = listOf(TextContent(text), EmbeddedResource(TextResourceContents(
            uri = "bosca://graphql/results/$action", mimeType = "application/json", text = text))),
            structuredContent = value, isError = !response.errors.isNullOrEmpty())
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { CallToolResult(content = listOf(TextContent(failure.message ?: "GraphQL operation failed")), isError = true) }

    private fun search(response: GraphQLResponse, query: String, offset: Int, limit: Int): GraphQLResponse {
        val schema = GraphQLJson.decodeFromJsonElement(GraphQLSchemaSearch.serializer(), requireNotNull(response.data)).schema
        val matches = buildList {
            for (type in schema.types) {
                if (type.name.startsWith("__")) continue
                fun match(kind: String, name: String, description: String?) {
                    if (name.contains(query, ignoreCase = true) || description?.contains(query, ignoreCase = true) == true) add(buildJsonObject {
                        put("kind", kind); put("type", type.name); put("name", name)
                        description?.let { put("description", it) }
                    })
                }
                match("TYPE", type.name, type.description)
                type.fields.orEmpty().forEach { match("FIELD", it.name, it.description) }
                type.inputFields.orEmpty().forEach { match("INPUT_FIELD", it.name, it.description) }
            }
        }.sortedWith(compareBy({ it.getValue("type").jsonPrimitive.content }, { it.getValue("kind").jsonPrimitive.content }, { it.getValue("name").jsonPrimitive.content }))
        val page = matches.drop(offset).take(limit)
        return GraphQLResponse(data = buildJsonObject {
            put("results", JsonArray(page)); put("count", matches.size); put("offset", offset); put("limit", limit)
            put("hasMore", offset.toLong() + page.size < matches.size)
        })
    }

    private fun Map<String, JsonElement>.required(key: String): String {
        val value = get(key)
        require(value is JsonPrimitive && value.isString && value.content.isNotBlank()) { "Missing $key" }
        return value.content
    }

    private val NAME = Regex("[_A-Za-z][_0-9A-Za-z]*")
    private val TYPE_REF = """
        fragment McpTypeRef on __Type {
          kind name ofType { kind name ofType { kind name ofType { kind name
            ofType { kind name ofType { kind name ofType { kind name ofType { kind name } } } } } } }
        }
    """.trimIndent()
    private val ROOTS = """
        query McpGraphQLRoots {
          __schema {
            queryType { name fields { name description type { ...McpTypeRef } } }
            mutationType { name fields { name description type { ...McpTypeRef } } }
            subscriptionType { name fields { name description type { ...McpTypeRef } } }
          }
        }
        $TYPE_REF
    """.trimIndent()
    private val TYPE = """
        query McpGraphQLType(${'$'}name: String!) {
          __type(name: ${'$'}name) {
            kind name description specifiedByURL isOneOf
            fields(includeDeprecated: true) {
              name description isDeprecated deprecationReason
              args { ...McpInputValue }
              type { ...McpTypeRef }
            }
            inputFields { ...McpInputValue }
            enumValues(includeDeprecated: true) { name description isDeprecated deprecationReason }
            interfaces { kind name } possibleTypes { kind name }
          }
        }
        fragment McpInputValue on __InputValue { name description defaultValue type { ...McpTypeRef } }
        $TYPE_REF
    """.trimIndent()
    private val SEARCH = """
        query McpGraphQLSearch {
          __schema { types { name description fields { name description } inputFields { name description } } }
        }
    """.trimIndent()
}
