package bosca.ai.kit.tools.analytics

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import bosca.ai.chat.model.AnalyticsInvestigationKind
import bosca.ai.kit.agents.analytics.AnalyticsServices
import bosca.ai.kit.tools.KitTool
import bosca.analytics.model.AnalyticsQuery
import bosca.analytics.model.AnalyticsQueryExecutionParameterInput
import bosca.analytics.model.AnalyticsQueryInput
import bosca.analytics.model.QueryParameterType
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Lists saved analytics queries visible to the caller. */
class ListSavedQueriesTool(private val services: AnalyticsServices) :
    KitTool<ListSavedQueriesTool.Input, ListSavedQueriesTool.Output>(
        Input.serializer(), Output.serializer(), "list_saved_queries", "List saved analytics queries visible to the caller",
    ) {
    @Serializable data class Input(val offset: Long = 0, val limit: Int = 50)
    @Serializable data class Item(val id: String, val key: String, val name: String, val description: String)
    @Serializable data class Output(val queries: List<Item> = emptyList(), val count: Int = 0, val success: Boolean, val error: String? = null)

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val recorder = currentCoroutineContext()[InvestigationRecorder]
        val startedAt = recorder?.startedAt()
        val result = analyticsCall {
            val page = services.queryService.getQueries(input.offset.coerceAtLeast(0), input.limit.coerceIn(1, 200))
            services.queryPermissionEvaluator.filterAllowed(authentication, page, PermissionAction.VIEW)
        }
        val queries = result.value ?: return Output(success = false, error = result.error)
        recorder?.record(AnalyticsInvestigationKind.DISCOVERY, descriptor.name, "${queries.size} saved queries", startedAt = checkNotNull(startedAt))
        val items = queries.map { Item(it.id.toString(), it.key, it.name, it.description) }
        return Output(items, items.size, success = true)
    }
}

/** Retrieves a saved query, its typed parameters, and its probed output columns. */
class GetSavedQueryTool(private val services: AnalyticsServices) :
    KitTool<GetSavedQueryTool.Input, GetSavedQueryTool.Output>(
        Input.serializer(), Output.serializer(), "get_saved_query", "Get a saved analytics query by id or key, including parameters and output columns",
    ) {
    @Serializable data class Input(val id: String? = null, val key: String? = null)
    @Serializable data class Parameter(
        val parameter: String, val name: String, val description: String, val type: QueryParameterType,
        val arrayType: QueryParameterType, val defaultValue: JsonElement? = null, val required: Boolean,
    )
    @Serializable data class Column(val name: String, val typeName: String, val nullable: Boolean)
    @Serializable data class Output(
        val id: String = "", val key: String = "", val name: String = "", val description: String = "", val sql: String = "",
        val parameters: List<Parameter> = emptyList(), val columns: List<Column> = emptyList(),
        val refreshIntervalSeconds: Int? = null, val success: Boolean, val error: String? = null,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val recorder = currentCoroutineContext()[InvestigationRecorder]
        val startedAt = recorder?.startedAt()
        val result = analyticsCall {
            val query = services.resolveQuery(input.id, input.key)
                ?: error("Saved query not found; provide a valid id or key")
            services.queryPermissionEvaluator.verifyAllowed(authentication, query, PermissionAction.VIEW)
            Triple(query, services.queryService.getParameters(query.id), services.queryExecutionService.getColumns(query.id))
        }
        val (query, parameters, columns) = result.value ?: return Output(success = false, error = result.error)
        recorder?.record(AnalyticsInvestigationKind.DISCOVERY, descriptor.name, "saved query ${query.key}: ${columns.size} columns", startedAt = checkNotNull(startedAt))
        return Output(
            id = query.id.toString(), key = query.key, name = query.name, description = query.description, sql = query.query,
            parameters = parameters.map { Parameter(it.parameter, it.name, it.description, it.type, it.arrayType, it.defaultValue, it.required) },
            columns = columns.map { Column(it.name, it.typeName, it.nullable) },
            refreshIntervalSeconds = query.refreshIntervalSeconds, success = true,
        )
    }
}

/** Creates a saved query with a slugged, collision-free key. */
class CreateSavedQueryTool(private val services: AnalyticsServices) :
    KitTool<CreateSavedQueryTool.Input, CreateSavedQueryTool.Output>(
        Input.serializer(), Output.serializer(), savedQueryDescriptor("create_saved_query", create = true),
    ) {
    @Serializable data class Input(
        val name: String,
        val description: String = "",
        @property:LLMDescription("Read-only SQL stored by the saved query") val sql: String,
        val parameters: List<AnalyticsParameterInput> = emptyList(),
        val refreshIntervalSeconds: Int? = null,
    )
    @Serializable data class Output(val id: String = "", val key: String = "", val name: String = "", val success: Boolean, val error: String? = null)

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val recorder = currentCoroutineContext()[InvestigationRecorder]
        val startedAt = recorder?.startedAt()
        val result = analyticsCall {
            services.verifyCanManageAnalytics(authentication)
            val key = uniqueAnalyticsKey(input.name) { services.queryService.getQueryByKey(it) != null }
            services.queryService.addQuery(
                AnalyticsQueryInput(
                    key = key, name = input.name, description = input.description, query = input.sql,
                    parameters = input.parameters.map { it.toModel() }, refreshIntervalSeconds = input.refreshIntervalSeconds,
                ),
            )
        }
        val query = result.value ?: return Output(success = false, error = result.error)
        recorder?.record(AnalyticsInvestigationKind.ARTIFACT, descriptor.name, "saved query ${query.key} (${query.id})", startedAt = checkNotNull(startedAt))
        return Output(query.id.toString(), query.key, query.name, success = true)
    }
}

/** Updates selected fields of an existing saved query. */
class UpdateSavedQueryTool(private val services: AnalyticsServices) :
    KitTool<UpdateSavedQueryTool.Input, UpdateSavedQueryTool.Output>(
        Input.serializer(), Output.serializer(), savedQueryDescriptor("update_saved_query", create = false),
    ) {
    @Serializable data class Input(
        val id: String,
        val name: String? = null,
        val description: String? = null,
        val sql: String? = null,
        val parameters: List<AnalyticsParameterInput>? = null,
        val refreshIntervalSeconds: Int? = null,
        val clearRefreshInterval: Boolean = false,
    )
    @Serializable data class Output(val id: String = "", val key: String = "", val name: String = "", val success: Boolean, val error: String? = null)

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val recorder = currentCoroutineContext()[InvestigationRecorder]
        val startedAt = recorder?.startedAt()
        val result = analyticsCall {
            val existing = services.queryService.getQueryById(UUID.parse(input.id))
            services.queryPermissionEvaluator.verifyAllowed(authentication, existing, PermissionAction.MANAGE)
            val parameters = input.parameters?.map { it.toModel() } ?: services.queryService.getParameters(existing.id).map {
                bosca.analytics.model.AnalyticsQueryParameterInput(it.parameter, it.name, it.description, it.type, it.arrayType, it.defaultValue, it.required)
            }
            services.queryService.editQuery(
                AnalyticsQueryInput(
                    id = existing.id,
                    key = existing.key,
                    name = input.name ?: existing.name,
                    description = input.description ?: existing.description,
                    query = input.sql ?: existing.query,
                    parameters = parameters,
                    configuration = existing.configuration,
                    refreshIntervalSeconds = when {
                        input.clearRefreshInterval -> null
                        input.refreshIntervalSeconds != null -> input.refreshIntervalSeconds
                        else -> existing.refreshIntervalSeconds
                    },
                ),
            )
        }
        val query = result.value ?: return Output(success = false, error = result.error)
        recorder?.record(AnalyticsInvestigationKind.ARTIFACT, descriptor.name, "saved query ${query.key} (${query.id})", startedAt = checkNotNull(startedAt))
        return Output(query.id.toString(), query.key, query.name, success = true)
    }
}

/** Executes a saved query under its entity EXECUTE permission. */
class ExecuteSavedQueryTool(private val services: AnalyticsServices) :
    KitTool<ExecuteSavedQueryTool.Input, ExecuteSavedQueryTool.Output>(
        Input.serializer(), Output.serializer(), executeSavedQueryDescriptor(),
    ) {
    @Serializable data class Parameter(val parameter: String, val value: JsonElement)
    @Serializable data class Input(val id: String? = null, val key: String? = null, val parameters: List<Parameter> = emptyList())
    @Serializable data class Output(
        val queryId: String = "", val queryKey: String = "", val records: List<JsonElement> = emptyList(), val rowCount: Int = 0,
        val cached: Boolean = false, val refreshedAt: String? = null, val success: Boolean, val error: String? = null,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val recorder = currentCoroutineContext()[InvestigationRecorder]
        val startedAt = recorder?.startedAt()
        val startedNanos = System.nanoTime()
        val result = analyticsCall {
            val query = services.resolveQuery(input.id, input.key)
                ?: error("Saved query not found; provide a valid id or key")
            services.queryPermissionEvaluator.verifyAllowed(authentication, query, PermissionAction.EXECUTE)
            val parameters = input.parameters.map { AnalyticsQueryExecutionParameterInput(it.parameter, it.value) }
            query to services.queryExecutionService.execute(query.id, parameters)
        }
        val (query, response) = result.value ?: return Output(success = false, error = result.error)
        recorder?.record(
            AnalyticsInvestigationKind.SAVED_QUERY,
            descriptor.name,
            "${response.records.size} rows in ${(System.nanoTime() - startedNanos) / 1_000_000} ms; cached=${response.cached}",
            sql = query.query,
            startedAt = checkNotNull(startedAt),
        )
        return Output(
            queryId = query.id.toString(), queryKey = query.key, records = response.records, rowCount = response.records.size,
            cached = response.cached, refreshedAt = response.refreshedAt?.toString(), success = true,
        )
    }
}

private suspend fun AnalyticsServices.resolveQuery(id: String?, key: String?): AnalyticsQuery? = when {
    !id.isNullOrBlank() -> queryService.getQueryById(UUID.parse(id))
    !key.isNullOrBlank() -> queryService.getQueryByKey(key)
    else -> null
}

private fun savedQueryDescriptor(name: String, create: Boolean) = ToolDescriptor(
    name = name,
    description = if (create) {
        "Create a saved analytics query with typed parameters and an optional refresh interval. The key is generated from the name."
    } else {
        "Update a saved analytics query. Omitted fields retain their current values."
    },
    requiredParameters = buildList {
        if (create) {
            add(ToolParameterDescriptor("name", "Saved query display name", ToolParameterType.String))
            add(ToolParameterDescriptor("sql", "Read-only SQL stored by the saved query", ToolParameterType.String))
        } else {
            add(ToolParameterDescriptor("id", "Saved query UUID", ToolParameterType.String))
        }
    },
    optionalParameters = buildList {
        if (!create) {
            add(ToolParameterDescriptor("name", "New display name", ToolParameterType.String))
            add(ToolParameterDescriptor("sql", "Replacement read-only SQL", ToolParameterType.String))
        }
        add(ToolParameterDescriptor("description", "Human-readable description", ToolParameterType.String))
        add(ToolParameterDescriptor("parameters", "Typed parameter declarations", ToolParameterType.List(QUERY_PARAMETER_DECLARATION)))
        add(ToolParameterDescriptor("refreshIntervalSeconds", "Result refresh/cache interval in seconds", ToolParameterType.Integer))
        if (!create) add(ToolParameterDescriptor("clearRefreshInterval", "Disable scheduled result caching", ToolParameterType.Boolean))
    },
)

private fun executeSavedQueryDescriptor() = ToolDescriptor(
    name = "execute_saved_query",
    description = "Execute a saved analytics query by id or key with typed JSON parameter values",
    optionalParameters = listOf(
        ToolParameterDescriptor("id", "Saved query UUID", ToolParameterType.String),
        ToolParameterDescriptor("key", "Saved query key", ToolParameterType.String),
        ToolParameterDescriptor("parameters", "Runtime parameter bindings", ToolParameterType.List(EXECUTION_PARAMETER)),
    ),
)

private val JSON_VALUE = ToolParameterType.AnyOf(
    arrayOf(
        ToolParameterDescriptor("string", "String JSON value", ToolParameterType.String),
        ToolParameterDescriptor("integer", "Integer JSON value", ToolParameterType.Integer),
        ToolParameterDescriptor("float", "Floating-point JSON value", ToolParameterType.Float),
        ToolParameterDescriptor("boolean", "Boolean JSON value", ToolParameterType.Boolean),
        ToolParameterDescriptor("object", "JSON object value", ToolParameterType.Object(emptyList(), additionalProperties = true)),
        ToolParameterDescriptor("array", "JSON array value", ToolParameterType.List(ToolParameterType.String)),
        ToolParameterDescriptor("null", "JSON null", ToolParameterType.Null),
    ),
)
private val QUERY_PARAMETER_DECLARATION = ToolParameterType.Object(
    properties = listOf(
        ToolParameterDescriptor("parameter", "SQL parameter token", ToolParameterType.String),
        ToolParameterDescriptor("name", "Display name", ToolParameterType.String),
        ToolParameterDescriptor("description", "Description", ToolParameterType.String),
        ToolParameterDescriptor("type", "STRING, INTEGER, FLOAT, BOOLEAN, DATE, TIME, DATETIME, ARRAY, or OBJECT", ToolParameterType.Enum(QueryParameterType.entries)),
        ToolParameterDescriptor("arrayType", "ARRAY element type", ToolParameterType.Enum(QueryParameterType.entries)),
        ToolParameterDescriptor("defaultValue", "Optional JSON default value", JSON_VALUE),
        ToolParameterDescriptor("required", "Whether the value is required", ToolParameterType.Boolean),
    ),
    requiredProperties = listOf("parameter", "name", "type"),
)
private val EXECUTION_PARAMETER = ToolParameterType.Object(
    properties = listOf(
        ToolParameterDescriptor("parameter", "Parameter token", ToolParameterType.String),
        ToolParameterDescriptor("value", "JSON value to bind", JSON_VALUE),
    ),
    requiredProperties = listOf("parameter", "value"),
)
