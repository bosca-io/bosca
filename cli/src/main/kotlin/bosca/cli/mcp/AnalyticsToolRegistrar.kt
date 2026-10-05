package bosca.cli.mcp

import bosca.cli.analytics.analyticsJson
import bosca.cli.analytics.enumValue
import bosca.cli.analytics.recordsJson
import bosca.cli.analytics.toJson
import bosca.cli.analytics.toQueryParameterInput
import bosca.cli.analytics.toVisualizationInstanceInput
import bosca.cli.api.AnalyticsApi
import bosca.graphql.gen.AnalyticsDashboardInput
import bosca.graphql.gen.AnalyticsQueryExecutionParameterInput
import bosca.graphql.gen.AnalyticsQueryInput
import bosca.graphql.gen.AnalyticsQueryParameterInput
import bosca.graphql.gen.AnalyticsVisualizationInput
import bosca.graphql.gen.AnalyticsVisualizationType
import bosca.graphql.gen.IAnalyticsQuerySummaryFragment
import bosca.graphql.gen.PermissionAction
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Registers analytics tools designed for model clients. Each Bosca concept is
 * one action-based tool so Codex and Claude can discover the domain without
 * loading a separate schema for every CRUD operation.
 */
object AnalyticsToolRegistrar {

    internal val queryTool = Tool(
        name = "analytics_query",
        description = """
            Work with saved analytics queries. Actions: list, get, execute, create, update, delete,
            refresh, grant_permission, revoke_permission. Prefer stable `key` identifiers when known.
            `execute` accepts a JSON object of named parameters and returns bounded structured rows.
            Updates preserve omitted fields; use the explicit clear flags to remove optional values.
        """.trimIndent(),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                actionProperty(
                    "list", "get", "execute", "create", "update", "delete", "refresh",
                    "grant_permission", "revoke_permission",
                )
                identifierProperties("query")
                stringProperty("newKey", "Replacement stable key (update)")
                stringProperty("name", "Display name (create/update)")
                stringProperty("description", "Description (create/update)")
                stringProperty("query", "SQL query text (create/update)")
                objectProperty("configuration", "Query configuration (create/update)")
                booleanProperty("clearConfiguration", "Remove configuration (update)")
                integerProperty("refreshIntervalSeconds", "Cached-result refresh interval in seconds")
                booleanProperty("clearRefreshInterval", "Remove the refresh interval (update)")
                arrayProperty(
                    "parameterDefinitions",
                    "Replacement query parameter definitions. Each needs parameter and type; optional name, description, arrayType, defaultValue, required.",
                )
                objectProperty(
                    "parameters",
                    "Named values for execute, for example {\"start\":\"2026-01-01\",\"limit\":25}",
                    additionalProperties = true,
                )
                integerProperty("limit", "Maximum list entries or execution rows returned (default 50; max 1000)")
                stringProperty("groupId", "Security group UUID (permission actions)")
                permissionActionProperty()
            },
            required = listOf("action"),
        ),
    )

    internal val visualizationTool = Tool(
        name = "analytics_visualization",
        description = """
            Work with reusable analytics visualizations. Actions: list, get, create, update, delete,
            grant_permission, revoke_permission. A visualization references a saved query and stores
            renderer configuration. Updates preserve omitted fields; explicit clear flags remove links
            or configuration. Types: NUMBER, BAR, LINE, PIE, DOUGHNUT, BUBBLE, SCATTER, TABLE,
            LABEL, DATEPICKER, TOPO_JSON_MAP, GEO_POINT_MAP, LIVE_SESSIONS_MAP.
            Common configuration keys are x/y for charts, label/value for pie-family previews,
            value for numbers, and columns for tables.
        """.trimIndent(),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                actionProperty("list", "get", "create", "update", "delete", "grant_permission", "revoke_permission")
                identifierProperties("visualization")
                stringProperty("newKey", "Replacement stable key (update)")
                stringProperty("name", "Display name (create/update)")
                stringProperty("description", "Description (create/update)")
                stringProperty("queryId", "Saved analytics query UUID")
                booleanProperty("clearQueryId", "Remove the saved-query link (update)")
                enumProperty(
                    "type",
                    "Visualization type (create/update)",
                    "NUMBER", "BAR", "LINE", "PIE", "DOUGHNUT", "BUBBLE", "SCATTER", "TABLE",
                    "LABEL", "DATEPICKER", "TOPO_JSON_MAP", "GEO_POINT_MAP", "LIVE_SESSIONS_MAP",
                )
                objectProperty(
                    "configuration",
                    "Renderer configuration: x/y for charts, label/value for pie, value for numbers, columns for tables",
                )
                booleanProperty("clearConfiguration", "Remove configuration (update)")
                integerProperty("limit", "Maximum list entries returned (default 50; max 1000)")
                stringProperty("groupId", "Security group UUID (permission actions)")
                permissionActionProperty()
            },
            required = listOf("action"),
        ),
    )

    internal val dashboardTool = Tool(
        name = "analytics_dashboard",
        description = """
            Work with analytics dashboards. Actions: list, get, create, update, delete,
            add_visualization, remove_visualization, grant_permission, revoke_permission.
            Dashboards arrange saved visualization instances. Updates preserve omitted fields;
            explicit clear flags remove optional configuration or parameters.
        """.trimIndent(),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                actionProperty(
                    "list", "get", "create", "update", "delete", "add_visualization",
                    "remove_visualization", "grant_permission", "revoke_permission",
                )
                identifierProperties("dashboard")
                stringProperty("newKey", "Replacement stable key (update)")
                stringProperty("name", "Display name (create/update)")
                stringProperty("description", "Description (create/update)")
                objectProperty("configuration", "Dashboard configuration (create/update)")
                booleanProperty("clearConfiguration", "Remove dashboard configuration (update)")
                arrayProperty(
                    "parameterDefinitions",
                    "Replacement dashboard parameter definitions. Each needs parameter and type.",
                )
                booleanProperty("clearParameters", "Remove all dashboard parameters (update)")
                stringProperty("visualizationId", "Visualization UUID (add_visualization)")
                stringProperty("instanceId", "Dashboard visualization instance UUID (remove_visualization)")
                objectProperty("instanceConfiguration", "Per-dashboard visualization configuration")
                integerProperty("limit", "Maximum list entries returned (default 50; max 1000)")
                stringProperty("groupId", "Security group UUID (permission actions)")
                permissionActionProperty()
            },
            required = listOf("action"),
        ),
    )

    fun registerAll(server: Server, api: AnalyticsApi) {
        server.addTool(queryTool) { request -> result { handleQuery(api, request.arguments ?: error("Missing arguments")) } }
        server.addTool(visualizationTool) { request ->
            result { handleVisualization(api, request.arguments ?: error("Missing arguments")) }
        }
        server.addTool(dashboardTool) { request ->
            result { handleDashboard(api, request.arguments ?: error("Missing arguments")) }
        }
    }

    internal suspend fun handleQuery(api: AnalyticsApi, args: Map<String, JsonElement>): String =
        when (val action = args.str("action")) {
            "list" -> {
                val queries = api.listQueries().take(args.limit())
                analyticsJson.encodeToString(
                    JsonArray.serializer(),
                    JsonArray(queries.map(::queryListJson)),
                )
            }
            "get" -> analyticsJson.encodeToString(JsonElement.serializer(), args.resolveQuery(api).toJson())
            "execute" -> {
                val parameters = args.optObject("parameters").orEmpty().map { (name, value) ->
                    AnalyticsQueryExecutionParameterInput(parameter = name, value = value)
                }
                val execution = args.optStr("id")?.let {
                    api.executeQuery(Uuid.parse(it), parameters)
                } ?: args.optStr("key")?.let {
                    api.executeQueryByKey(it, parameters)
                } ?: error("Provide id or key")
                analyticsJson.encodeToString(
                    JsonElement.serializer(),
                    recordsJson(
                        execution.records,
                        execution.cached,
                        execution.refreshedAt?.toString(),
                        args.limit(),
                    ),
                )
            }
            "create" -> {
                val created = api.createQuery(
                    AnalyticsQueryInput(
                        id = null,
                        key = args.str("key"),
                        name = args.str("name"),
                        description = args.str("description"),
                        query = args.str("query"),
                        parameters = args.parameterDefinitions(),
                        configuration = args.optObject("configuration"),
                        refreshIntervalSeconds = args.optInt("refreshIntervalSeconds"),
                    ),
                )
                analyticsJson.encodeToString(JsonElement.serializer(), created.toJson())
            }
            "update" -> {
                val current = args.resolveQuery(api)
                val updated = api.updateQuery(
                    AnalyticsQueryInput(
                        id = current.id,
                        key = args.optStr("newKey") ?: current.key,
                        name = args.optStr("name") ?: current.name,
                        description = args.optStr("description") ?: current.description,
                        query = args.optStr("query") ?: current.query ?: error("Existing query text is unavailable"),
                        parameters = if ("parameterDefinitions" in args) {
                            args.parameterDefinitions()
                        } else {
                            current.parameters.sortedBy { it.sort }.map {
                                AnalyticsQueryParameterInput(
                                    parameter = it.parameter,
                                    name = it.name,
                                    description = it.description,
                                    type = it.type,
                                    arrayType = it.arrayType,
                                    defaultValue = it.defaultValue,
                                    required = it.required,
                                )
                            }
                        },
                        configuration = when {
                            args.optBool("clearConfiguration") == true -> null
                            "configuration" in args -> args.optObject("configuration")
                            else -> current.configuration
                        },
                        refreshIntervalSeconds = when {
                            args.optBool("clearRefreshInterval") == true -> null
                            "refreshIntervalSeconds" in args -> args.optInt("refreshIntervalSeconds")
                            else -> current.refreshIntervalSeconds
                        },
                    ),
                )
                analyticsJson.encodeToString(JsonElement.serializer(), updated.toJson())
            }
            "delete" -> {
                val id = args.resolveQuery(api).id
                """{"deleted":${api.deleteQuery(id)},"id":"$id"}"""
            }
            "refresh" -> {
                val id = args.resolveQuery(api).id
                """{"enqueued":${api.refreshQuery(id)},"id":"$id"}"""
            }
            "grant_permission", "revoke_permission" -> {
                val id = args.resolveQuery(api).id
                permissionResult(
                    id,
                    args,
                    action == "grant_permission",
                    api::setQueryPermission,
                )
            }
            else -> error("Unknown analytics_query action: $action")
        }

    internal suspend fun handleVisualization(api: AnalyticsApi, args: Map<String, JsonElement>): String =
        when (val action = args.str("action")) {
            "list" -> {
                val values = api.listVisualizations().take(args.limit())
                analyticsJson.encodeToString(JsonArray.serializer(), JsonArray(values.map { it.toJson() }))
            }
            "get" -> analyticsJson.encodeToString(JsonElement.serializer(), args.resolveVisualization(api).toJson())
            "create" -> {
                val created = api.createVisualization(
                    AnalyticsVisualizationInput(
                        id = null,
                        key = args.str("key"),
                        name = args.str("name"),
                        description = args.str("description"),
                        queryId = args.optStr("queryId")?.let(Uuid::parse),
                        type = enumValue<AnalyticsVisualizationType>(args.str("type")),
                        configuration = args.optObject("configuration"),
                    ),
                )
                analyticsJson.encodeToString(JsonElement.serializer(), created.toJson())
            }
            "update" -> {
                val current = args.resolveVisualization(api)
                val updated = api.updateVisualization(
                    AnalyticsVisualizationInput(
                        id = current.id,
                        key = args.optStr("newKey") ?: current.key,
                        name = args.optStr("name") ?: current.name,
                        description = args.optStr("description") ?: current.description,
                        queryId = when {
                            args.optBool("clearQueryId") == true -> null
                            "queryId" in args -> args.optStr("queryId")?.let(Uuid::parse)
                            else -> current.queryId
                        },
                        type = args.optStr("type")?.let { enumValue(it) } ?: current.type,
                        configuration = when {
                            args.optBool("clearConfiguration") == true -> null
                            "configuration" in args -> args.optObject("configuration")
                            else -> current.configuration
                        },
                    ),
                )
                analyticsJson.encodeToString(JsonElement.serializer(), updated.toJson())
            }
            "delete" -> {
                val id = args.resolveVisualization(api).id
                """{"deleted":${api.deleteVisualization(id)},"id":"$id"}"""
            }
            "grant_permission", "revoke_permission" -> {
                val id = args.resolveVisualization(api).id
                permissionResult(
                    id,
                    args,
                    action == "grant_permission",
                    api::setVisualizationPermission,
                )
            }
            else -> error("Unknown analytics_visualization action: $action")
        }

    internal suspend fun handleDashboard(api: AnalyticsApi, args: Map<String, JsonElement>): String =
        when (val action = args.str("action")) {
            "list" -> {
                val values = api.listDashboards().take(args.limit())
                analyticsJson.encodeToString(JsonArray.serializer(), JsonArray(values.map { it.toJson() }))
            }
            "get" -> analyticsJson.encodeToString(JsonElement.serializer(), args.resolveDashboard(api).toJson())
            "create" -> {
                val created = api.createDashboard(
                    AnalyticsDashboardInput(
                        id = null,
                        key = args.str("key"),
                        name = args.str("name"),
                        description = args.str("description"),
                        configuration = args.optObject("configuration"),
                        parameters = args.parameterDefinitions(),
                        visualizations = emptyList(),
                    ),
                )
                analyticsJson.encodeToString(JsonElement.serializer(), created.toJson())
            }
            "update" -> {
                val current = args.resolveDashboard(api)
                val updated = api.updateDashboard(
                    AnalyticsDashboardInput(
                        id = current.id,
                        key = args.optStr("newKey") ?: current.key,
                        name = args.optStr("name") ?: current.name,
                        description = args.optStr("description") ?: current.description,
                        configuration = when {
                            args.optBool("clearConfiguration") == true -> null
                            "configuration" in args -> args.optObject("configuration")
                            else -> current.configuration
                        },
                        parameters = when {
                            args.optBool("clearParameters") == true -> emptyList()
                            "parameterDefinitions" in args -> args.parameterDefinitions()
                            else -> current.parameters.orEmpty().map {
                                AnalyticsQueryParameterInput(
                                    parameter = it.parameter,
                                    name = it.name,
                                    description = it.description,
                                    type = it.type,
                                    arrayType = it.arrayType,
                                    defaultValue = it.defaultValue,
                                    required = it.required,
                                )
                            }
                        },
                        visualizations = current.visualizations.map {
                            buildJsonObject {
                                put("visualizationId", it.visualization.id.toString())
                                put("configuration", it.configuration ?: JsonNull)
                            }.toVisualizationInstanceInput()
                        },
                    ),
                )
                analyticsJson.encodeToString(JsonElement.serializer(), updated.toJson())
            }
            "delete" -> {
                val id = args.resolveDashboard(api).id
                """{"deleted":${api.deleteDashboard(id)},"id":"$id"}"""
            }
            "add_visualization" -> {
                val dashboardId = args.resolveDashboard(api).id
                val instanceId = api.addDashboardVisualization(
                    dashboardId,
                    Uuid.parse(args.str("visualizationId")),
                    args.optObject("instanceConfiguration"),
                )
                """{"added":true,"dashboardId":"$dashboardId","instanceId":"$instanceId"}"""
            }
            "remove_visualization" -> {
                val instanceId = Uuid.parse(args.str("instanceId"))
                """{"removed":${api.removeDashboardVisualization(instanceId)},"instanceId":"$instanceId"}"""
            }
            "grant_permission", "revoke_permission" -> {
                val id = args.resolveDashboard(api).id
                permissionResult(
                    id,
                    args,
                    action == "grant_permission",
                    api::setDashboardPermission,
                )
            }
            else -> error("Unknown analytics_dashboard action: $action")
        }

    private suspend fun result(block: suspend () -> String): CallToolResult =
        try {
            CallToolResult(content = listOf(TextContent(block())))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
        }

    private suspend fun permissionResult(
        id: Uuid,
        args: Map<String, JsonElement>,
        grant: Boolean,
        setPermission: suspend (Uuid, Uuid, PermissionAction, Boolean) -> Unit,
    ): String {
        val groupId = Uuid.parse(args.str("groupId"))
        val permissionAction = enumValue<PermissionAction>(args.str("permissionAction"))
        setPermission(id, groupId, permissionAction, grant)
        return buildJsonObject {
            put(if (grant) "granted" else "revoked", true)
            put("id", id.toString())
            put("groupId", groupId.toString())
            put("permissionAction", permissionAction.name)
        }.toString()
    }

    private suspend fun Map<String, JsonElement>.resolveQuery(api: AnalyticsApi) =
        optStr("id")?.let { api.getQuery(Uuid.parse(it)) }
            ?: optStr("key")?.let { api.getQueryByKey(it) ?: error("Analytics query not found: $it") }
            ?: error("Provide id or key")

    private suspend fun Map<String, JsonElement>.resolveVisualization(api: AnalyticsApi) =
        optStr("id")?.let { api.getVisualization(Uuid.parse(it)) }
            ?: optStr("key")?.let {
                api.getVisualizationByKey(it) ?: error("Analytics visualization not found: $it")
            }
            ?: error("Provide id or key")

    private suspend fun Map<String, JsonElement>.resolveDashboard(api: AnalyticsApi) =
        optStr("id")?.let { api.getDashboard(Uuid.parse(it)) }
            ?: optStr("key")?.let { api.getDashboardByKey(it) ?: error("Analytics dashboard not found: $it") }
            ?: error("Provide id or key")

    private fun Map<String, JsonElement>.parameterDefinitions(): List<AnalyticsQueryParameterInput> =
        (get("parameterDefinitions") as? JsonArray).orEmpty().map { it.jsonObject.toQueryParameterInput() }

    private fun Map<String, JsonElement>.str(key: String): String =
        get(key)?.jsonPrimitive?.content ?: error("Missing required field: $key")

    private fun Map<String, JsonElement>.optStr(key: String): String? =
        get(key)?.jsonPrimitive?.contentOrNull

    private fun Map<String, JsonElement>.optInt(key: String): Int? =
        get(key)?.jsonPrimitive?.intOrNull

    private fun Map<String, JsonElement>.optBool(key: String): Boolean? =
        get(key)?.jsonPrimitive?.booleanOrNull

    private fun Map<String, JsonElement>.optObject(key: String): JsonObject? =
        get(key)?.takeUnless { it is JsonNull } as? JsonObject

    private fun Map<String, JsonElement>.limit(): Int =
        (optInt("limit") ?: 50).coerceIn(1, 1_000)

    private fun kotlinx.serialization.json.JsonObjectBuilder.actionProperty(vararg actions: String) {
        put("action", buildJsonObject {
            put("type", "string")
            put("enum", JsonArray(actions.map(::JsonPrimitive)))
            put("description", "Operation to perform")
        })
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.identifierProperties(label: String) {
        stringProperty("id", "$label UUID")
        stringProperty("key", "Stable $label key")
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.permissionActionProperty() {
        enumProperty(
            "permissionAction",
            "Permission action",
            "VIEW", "LIST", "EDIT", "DELETE", "EXECUTE", "MANAGE", "IMPERSONATE",
        )
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.stringProperty(name: String, description: String) {
        put(name, buildJsonObject {
            put("type", "string")
            put("description", description)
        })
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.enumProperty(
        name: String,
        description: String,
        vararg values: String,
    ) {
        put(name, buildJsonObject {
            put("type", "string")
            put("description", description)
            put("enum", JsonArray(values.map(::JsonPrimitive)))
        })
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.booleanProperty(name: String, description: String) {
        put(name, buildJsonObject {
            put("type", "boolean")
            put("description", description)
        })
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.integerProperty(name: String, description: String) {
        put(name, buildJsonObject {
            put("type", "integer")
            put("description", description)
        })
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.objectProperty(
        name: String,
        description: String,
        additionalProperties: Boolean = false,
    ) {
        put(name, buildJsonObject {
            put("type", "object")
            put("description", description)
            if (additionalProperties) put("additionalProperties", true)
        })
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.arrayProperty(name: String, description: String) {
        put(name, buildJsonObject {
            put("type", "array")
            put("description", description)
            put("items", buildJsonObject { put("type", "object") })
        })
    }

    private fun queryListJson(query: IAnalyticsQuerySummaryFragment): JsonObject =
        buildJsonObject {
            put("id", query.id.toString())
            put("key", query.key)
            put("name", query.name)
            put("description", query.description)
            put("refreshIntervalSeconds", query.refreshIntervalSeconds)
            put("parameters", JsonArray(query.parameters.sortedBy { it.sort }.map {
                JsonPrimitive(it.parameter)
            }))
            put("columns", JsonArray(query.columns.map { JsonPrimitive(it.name) }))
        }
}
