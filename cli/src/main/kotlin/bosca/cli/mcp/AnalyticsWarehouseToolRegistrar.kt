package bosca.cli.mcp

import bosca.cli.api.AnalyticsApi
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/** Read-only warehouse investigation through the caller's scoped GraphQL connection. */
object AnalyticsWarehouseToolRegistrar {
    val tool = Tool(
        name = "analytics_sql",
        description = "Discover the real warehouse before composing SQL: catalogs, schemas, tables, describe, then execute. " +
            "execute runs one read-only query and returns exact SQL, typed columns, bounded records, timing and truncation evidence. " +
            "Requires an administrator or analytics manager and analytics:sql scope. Catalogs vary by deployment; never guess schema names or invent results. " +
            "Use standard saved metric queries for user/session/engagement questions as described in server instructions. " +
            "Cross-check surprising results with small queries. Report only returned evidence and disclose truncation. " +
            "For a chart set visualizationType and configuration using actual result column names. " +
            "Use analytics_query, analytics_visualization and analytics_dashboard to save requested artifacts; inspect before editing. " +
            "Include returned SQL verbatim and artifact ids/keys in your answer.",
        inputSchema = ToolSchema(properties = buildJsonObject {
            putJsonObject("action") { put("type", "string"); put("enum", JsonArray(listOf("catalogs", "schemas", "tables", "describe", "execute").map(::JsonPrimitive))) }
            for (field in listOf("catalog", "schema", "table", "sql")) putJsonObject(field) { put("type", "string") }
            putJsonObject("offset") { put("type", "integer"); put("minimum", 0); put("description", "Discovery list offset") }
            putJsonObject("limit") { put("type", "integer"); put("minimum", 1); put("maximum", 1000); put("default", 100) }
            putJsonObject("visualizationType") { put("type", "string"); put("enum", JsonArray(chartTypes.map(::JsonPrimitive))) }
            putJsonObject("configuration") { put("type", "object"); put("description", "Chart columns: x/y, label/value, or columns. y and columns accept lists.") }
        }, required = listOf("action")),
        annotations = ToolAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = true),
    )

    fun registerAll(server: Server, api: AnalyticsApi) {
        server.addTool(tool) { request -> handle(api, request.arguments.orEmpty()) }
    }

    internal suspend fun handle(api: AnalyticsApi, args: Map<String, JsonElement>): CallToolResult = try {
        val action = args.required("action")
        val offset = args["offset"]?.jsonPrimitive?.int ?: 0
        val limit = args["limit"]?.jsonPrimitive?.int ?: 100
        require(offset >= 0 && limit in 1..1000) { "Use a nonnegative offset and a limit between 1 and 1000" }
        val result: JsonElement = when (action) {
            "catalogs" -> JsonArray(api.listCatalogs(offset, limit).map(::JsonPrimitive))
            "schemas" -> JsonArray(api.listSchemas(args.required("catalog"), offset, limit).map(::JsonPrimitive))
            "tables" -> JsonArray(api.listTables(args.required("catalog"), args.required("schema"), offset, limit).map(::JsonPrimitive))
            "describe" -> JsonArray(api.describeTable(args.required("catalog"), args.required("schema"), args.required("table")).map {
                buildJsonObject { put("name", it.name); put("typeName", it.typeName); put("nullable", it.nullable) }
            })
            "execute" -> {
                require(offset == 0) { "offset applies only to discovery lists; paginate SQL explicitly" }
                val chart = args["visualizationType"]?.jsonPrimitive?.content ?: "TABLE"
                require(chart in chartTypes) { "Unsupported visualizationType" }
                val configuration = args["configuration"]?.jsonObject ?: JsonObject(emptyMap())
                require(configuration.keys.all { it in setOf("x", "y", "label", "value", "columns") }) { "Unsupported chart configuration field" }
                val execution = api.executeSql(args.required("sql"), limit)
                buildJsonObject {
                    put("sql", execution.sql)
                    put("columns", JsonArray(execution.columns.map { buildJsonObject {
                        put("name", it.name); put("typeName", it.typeName); put("nullable", it.nullable)
                    } }))
                    put("records", JsonArray(execution.records)); put("returnedRecords", execution.records.size)
                    put("truncated", execution.truncated); put("cached", false); put("refreshedAt", JsonNull)
                    put("executedAt", execution.executedAt.toString()); put("durationMs", execution.durationMs)
                    put("visualizationType", chart); put("configuration", configuration)
                }
            }
            else -> error("Unknown analytics_sql action: $action")
        }
        val text = result.toString()
        CallToolResult(content = buildList {
            add(TextContent(text))
            if (action == "execute") add(EmbeddedResource(TextResourceContents(
                uri = "bosca://analytics/sql/${Uuid.random()}/results", mimeType = "application/json", text = text,
            )))
        })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CallToolResult(content = listOf(TextContent(e.message ?: "Analytics warehouse request failed")), isError = true)
    }

    private fun Map<String, JsonElement>.required(name: String): String =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            ?: error("Missing required string: $name")

    private val chartTypes get() = listOf("NUMBER", "BAR", "LINE", "PIE", "TABLE")
}
