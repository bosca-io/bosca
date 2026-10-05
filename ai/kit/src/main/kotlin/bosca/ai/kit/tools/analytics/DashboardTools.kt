package bosca.ai.kit.tools.analytics

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import bosca.ai.chat.model.AnalyticsInvestigationKind
import bosca.ai.kit.agents.analytics.AnalyticsServices
import bosca.ai.kit.tools.KitTool
import bosca.analytics.model.AnalyticsDashboard
import bosca.analytics.model.AnalyticsDashboardInput
import bosca.analytics.model.AnalyticsQueryParameterInput
import bosca.analytics.model.AnalyticsVisualizationInstanceInput
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Lists analytics dashboards visible to the caller. */
class ListDashboardsTool(private val services: AnalyticsServices) :
    KitTool<ListDashboardsTool.Input, ListDashboardsTool.Output>(
        Input.serializer(), Output.serializer(), "list_dashboards", "List analytics dashboards visible to the caller",
    ) {
    @Serializable data class Input(val offset: Long = 0, val limit: Int = 50)
    @Serializable data class Item(val id: String, val key: String, val name: String, val description: String)
    @Serializable data class Output(val dashboards: List<Item> = emptyList(), val count: Int = 0, val success: Boolean, val error: String? = null)

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val recorder = currentCoroutineContext()[InvestigationRecorder]
        val startedAt = recorder?.startedAt()
        val result = analyticsCall {
            val page = services.dashboardService.getDashboards(input.offset.coerceAtLeast(0), input.limit.coerceIn(1, 200))
            services.dashboardPermissionEvaluator.filterAllowed(authentication, page, PermissionAction.VIEW)
        }
        val dashboards = result.value ?: return Output(success = false, error = result.error)
        recorder?.record(AnalyticsInvestigationKind.DISCOVERY, descriptor.name, "${dashboards.size} dashboards", startedAt = checkNotNull(startedAt))
        val items = dashboards.map { Item(it.id.toString(), it.key, it.name, it.description) }
        return Output(items, items.size, success = true)
    }
}

/** Retrieves a dashboard and its current visualization instances. */
class GetDashboardTool(private val services: AnalyticsServices) :
    KitTool<GetDashboardTool.Input, GetDashboardTool.Output>(
        Input.serializer(), Output.serializer(), "get_dashboard", "Get an analytics dashboard by id or key, including visualization instances",
    ) {
    @Serializable data class Input(val id: String? = null, val key: String? = null)
    @Serializable data class Visualization(
        val instanceId: String, val visualizationId: String, val key: String, val name: String,
        val type: String, val configuration: JsonElement,
    )
    @Serializable data class Output(
        val id: String = "", val key: String = "", val name: String = "", val description: String = "",
        val configuration: JsonElement? = null, val parameters: List<AnalyticsParameterInput> = emptyList(),
        val visualizations: List<Visualization> = emptyList(), val success: Boolean, val error: String? = null,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val recorder = currentCoroutineContext()[InvestigationRecorder]
        val startedAt = recorder?.startedAt()
        val result = analyticsCall {
            val dashboard = services.resolveDashboard(input.id, input.key)
                ?: error("Dashboard not found; provide a valid id or key")
            services.dashboardPermissionEvaluator.verifyAllowed(authentication, dashboard, PermissionAction.VIEW)
            val visualizations = services.dashboardService.getVisualizations(dashboard.id).filter {
                services.visualizationPermissionEvaluator.isAllowed(authentication, it.visualization, PermissionAction.VIEW)
            }
            dashboard to visualizations
        }
        val (dashboard, visualizations) = result.value ?: return Output(success = false, error = result.error)
        recorder?.record(AnalyticsInvestigationKind.DISCOVERY, descriptor.name, "dashboard ${dashboard.key}: ${visualizations.size} visualizations", startedAt = checkNotNull(startedAt))
        return Output(
            id = dashboard.id.toString(), key = dashboard.key, name = dashboard.name, description = dashboard.description,
            configuration = dashboard.configuration, parameters = services.decodeParameters(dashboard.parameters),
            visualizations = visualizations.map {
                Visualization(
                    it.id.toString(), it.visualization.id.toString(), it.visualization.key,
                    it.visualization.name, it.visualization.type.name, it.configuration,
                )
            },
            success = true,
        )
    }
}

/** Creates an analytics dashboard with optional initial visualization instances. */
class CreateDashboardTool(private val services: AnalyticsServices) :
    KitTool<CreateDashboardTool.Input, CreateDashboardTool.Output>(Input.serializer(), Output.serializer(), dashboardDescriptor("create_dashboard", true)) {
    @Serializable data class Visualization(val visualizationId: String, val configuration: JsonElement)
    @Serializable data class Input(
        val name: String,
        val description: String = "",
        val configuration: JsonElement = JsonObject(emptyMap()),
        val parameters: List<AnalyticsParameterInput> = emptyList(),
        val visualizations: List<Visualization> = emptyList(),
    )
    @Serializable data class Output(val id: String = "", val key: String = "", val name: String = "", val success: Boolean, val error: String? = null)

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val recorder = currentCoroutineContext()[InvestigationRecorder]
        val startedAt = recorder?.startedAt()
        val result = analyticsCall {
            services.verifyCanManageDashboards(authentication)
            require(input.configuration is JsonObject) { "Dashboard configuration must be a JSON object" }
            val instances = input.visualizations.map { candidate ->
                val visualization = services.visualizationService.getVisualizationById(UUID.parse(candidate.visualizationId))
                services.visualizationPermissionEvaluator.verifyAllowed(authentication, visualization, PermissionAction.VIEW)
                require(candidate.configuration is JsonObject) { "Dashboard visualization configuration must be a JSON object" }
                AnalyticsVisualizationInstanceInput(visualization.id, candidate.configuration)
            }
            val key = uniqueAnalyticsKey(input.name) { services.dashboardService.getDashboardByKey(it) != null }
            services.dashboardService.addDashboard(
                AnalyticsDashboardInput(
                    key = key, name = input.name, description = input.description, configuration = input.configuration,
                    parameters = input.parameters.map { it.toModel() }, visualizations = instances,
                ),
            )
        }
        val dashboard = result.value ?: return Output(success = false, error = result.error)
        recorder?.record(AnalyticsInvestigationKind.ARTIFACT, descriptor.name, "dashboard ${dashboard.key} (${dashboard.id})", startedAt = checkNotNull(startedAt))
        return Output(dashboard.id.toString(), dashboard.key, dashboard.name, success = true)
    }
}

/** Updates selected dashboard fields while preserving omitted parameters and instances. */
class UpdateDashboardTool(private val services: AnalyticsServices) :
    KitTool<UpdateDashboardTool.Input, UpdateDashboardTool.Output>(Input.serializer(), Output.serializer(), dashboardDescriptor("update_dashboard", false)) {
    @Serializable data class Visualization(val visualizationId: String, val configuration: JsonElement)
    @Serializable data class Input(
        val id: String,
        val name: String? = null,
        val description: String? = null,
        val configuration: JsonElement? = null,
        val parameters: List<AnalyticsParameterInput>? = null,
        val visualizations: List<Visualization>? = null,
    )
    @Serializable data class Output(val id: String = "", val key: String = "", val name: String = "", val success: Boolean, val error: String? = null)

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val recorder = currentCoroutineContext()[InvestigationRecorder]
        val startedAt = recorder?.startedAt()
        val result = analyticsCall {
            val existing = services.dashboardService.getDashboardById(UUID.parse(input.id))
            services.dashboardPermissionEvaluator.verifyAllowed(authentication, existing, PermissionAction.MANAGE)
            val configuration = input.configuration ?: existing.configuration
            require(configuration is JsonObject) { "Dashboard configuration must be a JSON object" }
            val instances = input.visualizations?.map { candidate ->
                val visualization = services.visualizationService.getVisualizationById(UUID.parse(candidate.visualizationId))
                services.visualizationPermissionEvaluator.verifyAllowed(authentication, visualization, PermissionAction.VIEW)
                require(candidate.configuration is JsonObject) { "Dashboard visualization configuration must be a JSON object" }
                AnalyticsVisualizationInstanceInput(visualization.id, candidate.configuration)
            } ?: services.dashboardService.getVisualizations(existing.id).map {
                AnalyticsVisualizationInstanceInput(it.visualization.id, it.configuration)
            }
            services.dashboardService.editDashboard(
                AnalyticsDashboardInput(
                    id = existing.id, key = existing.key, name = input.name ?: existing.name,
                    description = input.description ?: existing.description, configuration = configuration,
                    parameters = input.parameters?.map { it.toModel() } ?: services.decodeParameters(existing.parameters).map { it.toModel() },
                    visualizations = instances,
                ),
            )
        }
        val dashboard = result.value ?: return Output(success = false, error = result.error)
        recorder?.record(AnalyticsInvestigationKind.ARTIFACT, descriptor.name, "dashboard ${dashboard.key} (${dashboard.id})", startedAt = checkNotNull(startedAt))
        return Output(dashboard.id.toString(), dashboard.key, dashboard.name, success = true)
    }
}

/** Adds a visualization instance, appending below existing content when placement is omitted. */
class AddDashboardVisualizationTool(private val services: AnalyticsServices) :
    KitTool<AddDashboardVisualizationTool.Input, AddDashboardVisualizationTool.Output>(Input.serializer(), Output.serializer(), DASHBOARD_ADD_DESCRIPTOR) {
    @Serializable data class Input(val dashboardId: String, val visualizationId: String, val configuration: JsonElement? = null)
    @Serializable data class Output(val instanceId: String = "", val configuration: JsonElement? = null, val success: Boolean, val error: String? = null)

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val recorder = currentCoroutineContext()[InvestigationRecorder]
        val startedAt = recorder?.startedAt()
        val result = analyticsCall {
            val dashboard = services.dashboardService.getDashboardById(UUID.parse(input.dashboardId))
            services.dashboardPermissionEvaluator.verifyAllowed(authentication, dashboard, PermissionAction.MANAGE)
            val visualization = services.visualizationService.getVisualizationById(UUID.parse(input.visualizationId))
            services.visualizationPermissionEvaluator.verifyAllowed(authentication, visualization, PermissionAction.VIEW)
            val existing = services.dashboardService.getVisualizations(dashboard.id)
            val supplied = input.configuration ?: JsonObject(emptyMap())
            require(supplied is JsonObject) { "Dashboard visualization configuration must be a JSON object" }
            val nextY = existing.maxOfOrNull { instance ->
                val config = instance.configuration as? JsonObject
                (config?.get("y")?.jsonPrimitive?.intOrNull ?: 0) + (config?.get("h")?.jsonPrimitive?.intOrNull ?: DEFAULT_HEIGHT)
            } ?: 0
            val defaults = buildJsonObject {
                put("x", 0); put("y", nextY); put("w", DEFAULT_WIDTH); put("h", DEFAULT_HEIGHT)
                put("showTitle", true); put("showBorder", true); put("showBackground", true)
                put("boldTitle", false); put("titleOverride", ""); put("titleSize", "sm"); put("locked", false)
            }
            val configuration = JsonObject(defaults + supplied)
            val instanceId = services.dashboardService.addVisualization(dashboard.id, visualization.id, configuration)
            instanceId to configuration
        }
        val (instanceId, configuration) = result.value ?: return Output(success = false, error = result.error)
        recorder?.record(AnalyticsInvestigationKind.ARTIFACT, descriptor.name, "dashboard visualization instance $instanceId", startedAt = checkNotNull(startedAt))
        return Output(instanceId.toString(), configuration, success = true)
    }
}

/** Removes one visualization instance from a dashboard without deleting either entity. */
class RemoveDashboardVisualizationTool(private val services: AnalyticsServices) :
    KitTool<RemoveDashboardVisualizationTool.Input, RemoveDashboardVisualizationTool.Output>(
        Input.serializer(), Output.serializer(), "remove_dashboard_visualization", "Remove a visualization instance from a dashboard without deleting the visualization entity",
    ) {
    @Serializable data class Input(val dashboardId: String, val instanceId: String)
    @Serializable data class Output(val instanceId: String = "", val success: Boolean, val error: String? = null)

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val recorder = currentCoroutineContext()[InvestigationRecorder]
        val startedAt = recorder?.startedAt()
        val result = analyticsCall {
            val dashboard = services.dashboardService.getDashboardById(UUID.parse(input.dashboardId))
            services.dashboardPermissionEvaluator.verifyAllowed(authentication, dashboard, PermissionAction.MANAGE)
            val instanceId = UUID.parse(input.instanceId)
            require(services.dashboardService.getVisualizations(dashboard.id).any { it.id == instanceId }) {
                "Visualization instance $instanceId does not belong to dashboard ${dashboard.id}"
            }
            services.dashboardService.removeVisualization(instanceId)
            instanceId
        }
        val instanceId = result.value ?: return Output(success = false, error = result.error)
        recorder?.record(AnalyticsInvestigationKind.ARTIFACT, descriptor.name, "removed dashboard visualization instance $instanceId", startedAt = checkNotNull(startedAt))
        return Output(instanceId.toString(), success = true)
    }
}

private suspend fun AnalyticsServices.resolveDashboard(id: String?, key: String?): AnalyticsDashboard? = when {
    !id.isNullOrBlank() -> dashboardService.getDashboardById(UUID.parse(id))
    !key.isNullOrBlank() -> dashboardService.getDashboardByKey(key)
    else -> null
}

private fun AnalyticsServices.decodeParameters(parameters: JsonElement?): List<AnalyticsParameterInput> {
    if (parameters == null) return emptyList()
    return json.decodeFromJsonElement(ListSerializer(AnalyticsQueryParameterInput.serializer()), parameters).map {
        AnalyticsParameterInput(it.parameter, it.name, it.description, it.type, it.arrayType, it.defaultValue, it.required)
    }
}

private fun dashboardDescriptor(name: String, create: Boolean) = ToolDescriptor(
    name = name,
    description = (if (create) "Create" else "Update") + " an analytics dashboard. Dashboard configuration is a JSON object. " +
        "Visualization placement configuration uses {x,y,w,h,showTitle,showBorder,showBackground,boldTitle,titleOverride,titleSize,locked}; Studio defaults to x=0, w=24, h=12 and appends at the next free y row.",
    requiredParameters = buildList {
        if (create) add(ToolParameterDescriptor("name", "Dashboard display name", ToolParameterType.String))
        else add(ToolParameterDescriptor("id", "Existing dashboard UUID", ToolParameterType.String))
    },
    optionalParameters = listOf(
        ToolParameterDescriptor("description", "Human-readable description", ToolParameterType.String),
        ToolParameterDescriptor("configuration", "Dashboard-level JSON configuration", OPEN_OBJECT),
        ToolParameterDescriptor("parameters", "Dashboard parameter declarations", ToolParameterType.List(PARAMETER_OBJECT)),
        ToolParameterDescriptor("visualizations", "Initial or replacement visualization instances", ToolParameterType.List(VISUALIZATION_INSTANCE_OBJECT)),
    ),
)

private val DASHBOARD_ADD_DESCRIPTOR = ToolDescriptor(
    name = "add_dashboard_visualization",
    description = "Add a visualization instance to a dashboard. Placement uses {x,y,w,h,showTitle,showBorder,showBackground,boldTitle,titleOverride,titleSize,locked}; omitted values receive Studio defaults and omitted y appends below existing content.",
    requiredParameters = listOf(
        ToolParameterDescriptor("dashboardId", "Dashboard UUID", ToolParameterType.String),
        ToolParameterDescriptor("visualizationId", "Visualization UUID", ToolParameterType.String),
    ),
    optionalParameters = listOf(ToolParameterDescriptor("configuration", "Placement and display configuration", dashboardPlacement())),
)

private val OPEN_OBJECT = ToolParameterType.Object(properties = emptyList(), additionalProperties = true)
private val PARAMETER_OBJECT = ToolParameterType.Object(
    properties = listOf(
        ToolParameterDescriptor("parameter", "SQL parameter token", ToolParameterType.String),
        ToolParameterDescriptor("name", "Display name", ToolParameterType.String),
        ToolParameterDescriptor("description", "Description", ToolParameterType.String),
        ToolParameterDescriptor("type", "Parameter type", ToolParameterType.String),
        ToolParameterDescriptor("arrayType", "ARRAY element type", ToolParameterType.String),
        ToolParameterDescriptor("required", "Whether the value is required", ToolParameterType.Boolean),
    ),
    requiredProperties = listOf("parameter", "name", "type"),
)
private fun dashboardPlacement() = ToolParameterType.Object(
    properties = listOf(
        "x", "y", "w", "h",
    ).map { ToolParameterDescriptor(it, "Grid coordinate or size", ToolParameterType.Integer) } + listOf(
        "showTitle", "showBorder", "showBackground", "boldTitle", "locked",
    ).map { ToolParameterDescriptor(it, "Display option", ToolParameterType.Boolean) } + listOf(
        ToolParameterDescriptor("titleOverride", "Optional instance title", ToolParameterType.String),
        ToolParameterDescriptor("titleSize", "sm, md, or lg", ToolParameterType.String),
    ),
)
private val VISUALIZATION_INSTANCE_OBJECT = ToolParameterType.Object(
    properties = listOf(
        ToolParameterDescriptor("visualizationId", "Visualization UUID", ToolParameterType.String),
        ToolParameterDescriptor("configuration", "Placement and display configuration", dashboardPlacement()),
    ),
    requiredProperties = listOf("visualizationId", "configuration"),
)

private const val DEFAULT_WIDTH = 24
private const val DEFAULT_HEIGHT = 12
