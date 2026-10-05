package bosca.ai.kit.tools.analytics

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import bosca.ai.chat.model.AnalyticsInvestigationKind
import bosca.ai.kit.agents.analytics.AnalyticsServices
import bosca.ai.kit.tools.KitTool
import bosca.analytics.model.AnalyticsVisualization
import bosca.analytics.model.AnalyticsVisualizationInput
import bosca.analytics.model.AnalyticsVisualizationType
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Lists saved analytics visualizations visible to the caller. */
class ListVisualizationsTool(private val services: AnalyticsServices) :
    KitTool<ListVisualizationsTool.Input, ListVisualizationsTool.Output>(
        Input.serializer(), Output.serializer(), "list_visualizations", "List saved analytics visualizations visible to the caller",
    ) {
    @Serializable data class Input(val offset: Long = 0, val limit: Int = 50)
    @Serializable data class Item(val id: String, val key: String, val name: String, val description: String, val type: AnalyticsVisualizationType, val queryId: String? = null)
    @Serializable data class Output(val visualizations: List<Item> = emptyList(), val count: Int = 0, val success: Boolean, val error: String? = null)

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val recorder = currentCoroutineContext()[InvestigationRecorder]
        val startedAt = recorder?.startedAt()
        val result = analyticsCall {
            val page = services.visualizationService.getVisualizations(input.offset.coerceAtLeast(0), input.limit.coerceIn(1, 200))
            services.visualizationPermissionEvaluator.filterAllowed(authentication, page, PermissionAction.VIEW)
        }
        val visualizations = result.value ?: return Output(success = false, error = result.error)
        recorder?.record(AnalyticsInvestigationKind.DISCOVERY, descriptor.name, "${visualizations.size} visualizations", startedAt = checkNotNull(startedAt))
        val items = visualizations.map { Item(it.id.toString(), it.key, it.name, it.description, it.type, it.queryId?.toString()) }
        return Output(items, items.size, success = true)
    }
}

/** Retrieves a saved analytics visualization by id or key. */
class GetVisualizationTool(private val services: AnalyticsServices) :
    KitTool<GetVisualizationTool.Input, GetVisualizationTool.Output>(
        Input.serializer(), Output.serializer(), "get_visualization", "Get a saved analytics visualization by id or key",
    ) {
    @Serializable data class Input(val id: String? = null, val key: String? = null)
    @Serializable data class Output(
        val id: String = "", val key: String = "", val name: String = "", val description: String = "",
        val type: AnalyticsVisualizationType? = null, val queryId: String? = null, val configuration: JsonElement? = null,
        val success: Boolean, val error: String? = null,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val recorder = currentCoroutineContext()[InvestigationRecorder]
        val startedAt = recorder?.startedAt()
        val result = analyticsCall {
            val visualization = services.resolveVisualization(input.id, input.key)
                ?: error("Visualization not found; provide a valid id or key")
            services.visualizationPermissionEvaluator.verifyAllowed(authentication, visualization, PermissionAction.VIEW)
            visualization
        }
        val visualization = result.value ?: return Output(success = false, error = result.error)
        recorder?.record(AnalyticsInvestigationKind.DISCOVERY, descriptor.name, "visualization ${visualization.key} (${visualization.id})", startedAt = checkNotNull(startedAt))
        return Output(
            visualization.id.toString(), visualization.key, visualization.name, visualization.description,
            visualization.type, visualization.queryId?.toString(), visualization.configuration, success = true,
        )
    }
}

/** Creates a visualization after validating query-column references in its configuration. */
class CreateVisualizationTool(private val services: AnalyticsServices) :
    KitTool<CreateVisualizationTool.Input, CreateVisualizationTool.Output>(Input.serializer(), Output.serializer(), visualizationDescriptor("create_visualization", true)) {
    @Serializable data class Input(
        val name: String,
        val description: String = "",
        val type: AnalyticsVisualizationType,
        val queryId: String? = null,
        val configuration: JsonElement,
    )
    @Serializable data class Output(val id: String = "", val key: String = "", val name: String = "", val success: Boolean, val error: String? = null)

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val recorder = currentCoroutineContext()[InvestigationRecorder]
        val startedAt = recorder?.startedAt()
        val result = analyticsCall {
            services.verifyCanManageAnalytics(authentication)
            val queryId = input.queryId?.takeIf { it.isNotBlank() }?.let(UUID::parse)
            services.validateVisualization(authentication, input.type, queryId, input.configuration)
            val key = uniqueAnalyticsKey(input.name) { services.visualizationService.getVisualizationByKey(it) != null }
            services.visualizationService.addVisualization(
                AnalyticsVisualizationInput(
                    key = key, name = input.name, description = input.description, queryId = queryId,
                    type = input.type, configuration = input.configuration,
                ),
            )
        }
        val visualization = result.value ?: return Output(success = false, error = result.error)
        recorder?.record(AnalyticsInvestigationKind.ARTIFACT, descriptor.name, "visualization ${visualization.key} (${visualization.id})", startedAt = checkNotNull(startedAt))
        return Output(visualization.id.toString(), visualization.key, visualization.name, success = true)
    }
}

/** Updates a visualization after validating query-column references in its configuration. */
class UpdateVisualizationTool(private val services: AnalyticsServices) :
    KitTool<UpdateVisualizationTool.Input, UpdateVisualizationTool.Output>(Input.serializer(), Output.serializer(), visualizationDescriptor("update_visualization", false)) {
    @Serializable data class Input(
        val id: String,
        val name: String? = null,
        val description: String? = null,
        val type: AnalyticsVisualizationType? = null,
        val queryId: String? = null,
        val clearQuery: Boolean = false,
        val configuration: JsonElement? = null,
    )
    @Serializable data class Output(val id: String = "", val key: String = "", val name: String = "", val success: Boolean, val error: String? = null)

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val recorder = currentCoroutineContext()[InvestigationRecorder]
        val startedAt = recorder?.startedAt()
        val result = analyticsCall {
            val existing = services.visualizationService.getVisualizationById(UUID.parse(input.id))
            services.visualizationPermissionEvaluator.verifyAllowed(authentication, existing, PermissionAction.MANAGE)
            val type = input.type ?: existing.type
            val queryId = when {
                input.clearQuery -> null
                !input.queryId.isNullOrBlank() -> UUID.parse(input.queryId)
                else -> existing.queryId
            }
            val configuration = input.configuration ?: existing.configuration
            services.validateVisualization(authentication, type, queryId, configuration)
            services.visualizationService.editVisualization(
                AnalyticsVisualizationInput(
                    id = existing.id, key = existing.key, name = input.name ?: existing.name,
                    description = input.description ?: existing.description, queryId = queryId,
                    type = type, configuration = configuration,
                ),
            )
        }
        val visualization = result.value ?: return Output(success = false, error = result.error)
        recorder?.record(AnalyticsInvestigationKind.ARTIFACT, descriptor.name, "visualization ${visualization.key} (${visualization.id})", startedAt = checkNotNull(startedAt))
        return Output(visualization.id.toString(), visualization.key, visualization.name, success = true)
    }
}

private suspend fun AnalyticsServices.resolveVisualization(id: String?, key: String?): AnalyticsVisualization? = when {
    !id.isNullOrBlank() -> visualizationService.getVisualizationById(UUID.parse(id))
    !key.isNullOrBlank() -> visualizationService.getVisualizationByKey(key)
    else -> null
}

private suspend fun AnalyticsServices.validateVisualization(
    authentication: AuthenticationContext,
    type: AnalyticsVisualizationType,
    queryId: UUID?,
    configuration: JsonElement,
) {
    val config = configuration as? JsonObject ?: error("Visualization configuration must be a JSON object")
    if (queryId == null) return
    val query = queryService.getQueryById(queryId)
    queryPermissionEvaluator.verifyAllowed(authentication, query, PermissionAction.VIEW)
    val available = queryExecutionService.getColumns(queryId).map { it.name }
    if (available.isEmpty()) error("Could not determine output columns for saved query '${query.key}'")
    val references = referencedColumns(type, config)
    val missing = references.filterNot(available::contains).distinct()
    if (missing.isNotEmpty()) {
        error("Visualization configuration references missing column(s): ${missing.joinToString()}. Available columns: ${available.joinToString()}")
    }
}

private fun referencedColumns(type: AnalyticsVisualizationType, configuration: JsonObject): List<String> {
    fun string(name: String) = configuration[name]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    fun strings(name: String): List<String> = when (val value = configuration[name]) {
        is JsonArray -> value.mapNotNull { it.jsonPrimitive.content.takeIf(String::isNotBlank) }
        null -> emptyList()
        else -> listOfNotNull(value.jsonPrimitive.content.takeIf(String::isNotBlank))
    }
    return when (type) {
        AnalyticsVisualizationType.BAR,
        AnalyticsVisualizationType.LINE,
        AnalyticsVisualizationType.SCATTER,
        AnalyticsVisualizationType.BUBBLE,
        AnalyticsVisualizationType.STACKED_AREA -> listOfNotNull(string("x")) + strings("y")
        AnalyticsVisualizationType.PIE,
        AnalyticsVisualizationType.DOUGHNUT -> listOfNotNull(string("label"), string("value"))
        AnalyticsVisualizationType.NUMBER -> listOfNotNull(string("value"))
        AnalyticsVisualizationType.TABLE -> strings("columns")
        AnalyticsVisualizationType.TOPO_JSON_MAP -> listOfNotNull(string("regionColumn"), string("valueColumn"))
        AnalyticsVisualizationType.GEO_POINT_MAP -> listOfNotNull(
            string("latitudeColumn"), string("longitudeColumn"), string("idColumn"), string("seriesColumn"),
        )
        AnalyticsVisualizationType.LABEL,
        AnalyticsVisualizationType.DATEPICKER,
        AnalyticsVisualizationType.LIVE_SESSIONS_MAP,
        AnalyticsVisualizationType.GANTT,
        AnalyticsVisualizationType.GRAPH -> emptyList()
    }
}

private fun visualizationDescriptor(name: String, create: Boolean) = ToolDescriptor(
    name = name,
    description = buildString {
        append(if (create) "Create" else "Update")
        append(" an analytics visualization. Configuration schemas: BAR/LINE/SCATTER/BUBBLE/STACKED_AREA {x:string,y:string[]?,xLabel?,yLabel?,fields?}; ")
        append("PIE/DOUGHNUT {label:string,value:string,fields?}; NUMBER {value:string,fields?}; TABLE {columns:string[]?,fields?}; ")
        append("LABEL {label,fontSize?,fontWeight?,alignment?}; DATEPICKER {startDateParam?,endDateParam?}; ")
        append("TOPO_JSON_MAP {regionColumn,valueColumn,fields?}; GEO_POINT_MAP {latitudeColumn,longitudeColumn,idColumn?,seriesColumn?,jitter?,pointRadius?,fields?}. ")
        append("GANTT and GRAPH configurations are persisted as supplied because the current shared renderer does not define their field contracts.")
    },
    requiredParameters = buildList {
        if (!create) add(ToolParameterDescriptor("id", "Existing visualization UUID", ToolParameterType.String))
        if (create) {
            add(ToolParameterDescriptor("name", "Visualization display name", ToolParameterType.String))
            add(ToolParameterDescriptor("type", "Visualization type", ToolParameterType.Enum(AnalyticsVisualizationType.entries)))
            add(ToolParameterDescriptor("configuration", "Type-specific configuration object described above", VISUALIZATION_CONFIGURATION))
        }
    },
    optionalParameters = buildList {
        if (!create) {
            add(ToolParameterDescriptor("name", "New display name", ToolParameterType.String))
            add(ToolParameterDescriptor("type", "New visualization type", ToolParameterType.Enum(AnalyticsVisualizationType.entries)))
            add(ToolParameterDescriptor("configuration", "Replacement type-specific configuration", VISUALIZATION_CONFIGURATION))
        }
        add(ToolParameterDescriptor("description", "Human-readable description", ToolParameterType.String))
        add(ToolParameterDescriptor("queryId", "Optional saved-query UUID", ToolParameterType.String))
        if (!create) add(ToolParameterDescriptor("clearQuery", "Remove the saved-query binding", ToolParameterType.Boolean))
    },
)

private val FIELD_SETTINGS = ToolParameterType.Object(
    properties = listOf(
        ToolParameterDescriptor("label", "Display label", ToolParameterType.String),
        ToolParameterDescriptor("type", "auto, string, number, or date", ToolParameterType.String),
        ToolParameterDescriptor("format", "Display format such as currency, percent, or a date-fns format", ToolParameterType.String),
    ),
)

private val VISUALIZATION_CONFIGURATION = ToolParameterType.Object(
    properties = listOf(
        "x", "xLabel", "yLabel", "label", "value", "fontSize", "fontWeight", "alignment",
        "startDateParam", "endDateParam", "regionColumn", "valueColumn", "latitudeColumn",
        "longitudeColumn", "idColumn", "seriesColumn",
    ).map { ToolParameterDescriptor(it, "See the per-type configuration contract", ToolParameterType.String) } + listOf(
        ToolParameterDescriptor("y", "One or more y-axis result columns", ToolParameterType.List(ToolParameterType.String)),
        ToolParameterDescriptor("columns", "Result columns to show in a table", ToolParameterType.List(ToolParameterType.String)),
        ToolParameterDescriptor("jitter", "Map point jitter in degrees", ToolParameterType.Float),
        ToolParameterDescriptor("pointRadius", "Map point radius", ToolParameterType.Float),
        ToolParameterDescriptor(
            "fields", "Per-column display settings", ToolParameterType.Object(
                properties = emptyList(), additionalProperties = true, additionalPropertiesType = FIELD_SETTINGS,
            ),
        ),
    ),
    additionalProperties = true,
)
