package bosca.cli.analytics

import bosca.cli.api.AnalyticsApi
import bosca.graphql.gen.AnalyticsDashboardInput
import bosca.graphql.gen.AnalyticsQueryParameterInput
import bosca.graphql.gen.AnalyticsVisualizationInstanceInput
import bosca.graphql.gen.PermissionAction
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.uuid.Uuid

class AnalyticsDashboardListCommand : AnalyticsSubcommand(name = "list") {
    override fun help(context: Context) = "List analytics dashboards"

    private val jsonOutput by option("--json", help = "Emit structured JSON").flag()
    private val limit by option("--limit", help = "Maximum dashboards to display").int().default(50)

    override suspend fun execute(api: AnalyticsApi) {
        val dashboards = api.listDashboards().take(limit.coerceIn(1, 1_000))
        if (jsonOutput) {
            echo(
                analyticsJson.encodeToString(
                    JsonArray.serializer(),
                    JsonArray(dashboards.map { it.toJson() }),
                ),
            )
            return
        }
        if (dashboards.isEmpty()) {
            echo("No analytics dashboards found.")
            return
        }
        echo("%-36s  %-24s  %-30s  %s".format("ID", "KEY", "NAME", "DESCRIPTION"))
        echo("-".repeat(125))
        dashboards.forEach { dashboard ->
            echo(
                "%-36s  %-24s  %-30s  %s".format(
                    dashboard.id,
                    dashboard.key.take(24),
                    dashboard.name.take(30),
                    dashboard.description.take(30),
                ),
            )
        }
    }
}

class AnalyticsDashboardGetCommand : AnalyticsSubcommand(name = "get") {
    override fun help(context: Context) = "Get an analytics dashboard by ID or key"

    private val id by option("--id", help = "Dashboard UUID")
    private val key by option("--key", "-k", help = "Stable dashboard key")

    override suspend fun execute(api: AnalyticsApi) {
        val dashboard = resolveDashboard(api, id, key)
        echo(analyticsJson.encodeToString(JsonElement.serializer(), dashboard.toJson()))
    }
}

class AnalyticsDashboardCreateCommand : AnalyticsSubcommand(name = "create") {
    override fun help(context: Context) = "Create an analytics dashboard"

    private val key by option("--key", "-k", help = "Stable dashboard key").required()
    private val name by option("--name", "-n", help = "Display name").required()
    private val description by option("--description", "-d", help = "Description").required()
    private val configurationJson by option(
        "--configuration-json",
        help = "Dashboard configuration JSON object. Supports @file.",
    )
    private val parameterJson by option(
        "--parameter-json",
        help = "Dashboard parameter definition JSON object; repeatable. Supports @file.",
    ).multiple()
    private val visualizationJson by option(
        "--visualization-json",
        help = "Visualization placement JSON object; repeatable. Supports @file.",
    ).multiple()

    override suspend fun execute(api: AnalyticsApi) {
        val created = api.createDashboard(
            AnalyticsDashboardInput(
                id = null,
                key = key,
                name = name,
                description = description,
                configuration = parseJsonObjectArgument(configurationJson, "configuration"),
                parameters = parameterJson.map(::parseDashboardParameterArgument),
                visualizations = visualizationJson.map(::parseVisualizationInstanceArgument),
            ),
        )
        echo(analyticsJson.encodeToString(JsonElement.serializer(), created.toJson()))
    }
}

class AnalyticsDashboardUpdateCommand : AnalyticsSubcommand(name = "update") {
    override fun help(context: Context) =
        "Update an analytics dashboard; unspecified fields are preserved"

    private val id by option("--id", help = "Dashboard UUID").required()
    private val key by option("--key", "-k", help = "New stable dashboard key")
    private val name by option("--name", "-n", help = "New display name")
    private val description by option("--description", "-d", help = "New description")
    private val configurationJson by option(
        "--configuration-json",
        help = "Replacement dashboard configuration JSON object. Supports @file.",
    )
    private val clearConfiguration by option("--clear-configuration").flag()
    private val parameterJson by option(
        "--parameter-json",
        help = "Replacement dashboard parameter definition JSON object; repeatable. Supports @file.",
    ).multiple()
    private val clearParameters by option("--clear-parameters").flag()
    private val visualizationJson by option(
        "--visualization-json",
        help = "Replacement visualization placement JSON object; repeatable. Supports @file.",
    ).multiple()
    private val clearVisualizations by option("--clear-visualizations").flag()

    override suspend fun execute(api: AnalyticsApi) {
        require(!(configurationJson != null && clearConfiguration)) {
            "Use only one of --configuration-json or --clear-configuration"
        }
        require(!(parameterJson.isNotEmpty() && clearParameters)) {
            "Use only one of --parameter-json or --clear-parameters"
        }
        require(!(visualizationJson.isNotEmpty() && clearVisualizations)) {
            "Use only one of --visualization-json or --clear-visualizations"
        }
        val dashboardId = Uuid.parse(id)
        val current = api.getDashboard(dashboardId)
        val updated = api.updateDashboard(
            AnalyticsDashboardInput(
                id = dashboardId,
                key = key ?: current.key,
                name = name ?: current.name,
                description = description ?: current.description,
                configuration = when {
                    clearConfiguration -> null
                    configurationJson != null ->
                        parseJsonObjectArgument(configurationJson, "configuration")
                    else -> current.configuration
                },
                parameters = when {
                    clearParameters -> emptyList()
                    parameterJson.isNotEmpty() ->
                        parameterJson.map(::parseDashboardParameterArgument)
                    else -> current.parameters.orEmpty().map { parameter ->
                        AnalyticsQueryParameterInput(
                            parameter = parameter.parameter,
                            name = parameter.name,
                            description = parameter.description,
                            type = parameter.type,
                            arrayType = parameter.arrayType,
                            defaultValue = parameter.defaultValue,
                            required = parameter.required,
                        )
                    }
                },
                visualizations = when {
                    clearVisualizations -> emptyList()
                    visualizationJson.isNotEmpty() ->
                        visualizationJson.map(::parseVisualizationInstanceArgument)
                    else -> current.visualizations.map { instance ->
                        AnalyticsVisualizationInstanceInput(
                            visualizationId = instance.visualization.id,
                            configuration = instance.configuration,
                        )
                    }
                },
            ),
        )
        echo(analyticsJson.encodeToString(JsonElement.serializer(), updated.toJson()))
    }
}

class AnalyticsDashboardDeleteCommand : AnalyticsSubcommand(name = "delete") {
    override fun help(context: Context) = "Delete an analytics dashboard"

    private val id by option("--id", help = "Dashboard UUID").required()

    override suspend fun execute(api: AnalyticsApi) {
        val dashboardId = Uuid.parse(id)
        require(api.deleteDashboard(dashboardId)) { "The analytics dashboard was not deleted" }
        echo("""{"deleted":true,"id":"$dashboardId"}""")
    }
}

class AnalyticsDashboardAddVisualizationCommand : AnalyticsSubcommand(name = "add-visualization") {
    override fun help(context: Context) = "Place a saved visualization on a dashboard"

    private val dashboardId by option("--dashboard-id", help = "Dashboard UUID").required()
    private val visualizationId by option("--visualization-id", help = "Visualization UUID").required()
    private val configurationJson by option(
        "--configuration-json",
        help = "Per-instance configuration JSON object. Supports @file.",
    )

    override suspend fun execute(api: AnalyticsApi) {
        val instanceId = api.addDashboardVisualization(
            dashboardId = Uuid.parse(dashboardId),
            visualizationId = Uuid.parse(visualizationId),
            configuration = parseJsonObjectArgument(configurationJson, "configuration"),
        )
        echo("""{"added":true,"instanceId":"$instanceId"}""")
    }
}

class AnalyticsDashboardRemoveVisualizationCommand : AnalyticsSubcommand(name = "remove-visualization") {
    override fun help(context: Context) = "Remove a visualization instance from a dashboard"

    private val instanceId by option("--instance-id", help = "Dashboard visualization instance UUID")
        .required()

    override suspend fun execute(api: AnalyticsApi) {
        val id = Uuid.parse(instanceId)
        require(api.removeDashboardVisualization(id)) { "The visualization instance was not removed" }
        echo("""{"removed":true,"instanceId":"$id"}""")
    }
}

class AnalyticsDashboardRenderCommand : AnalyticsSubcommand(name = "render") {
    override fun help(context: Context) =
        "Execute dashboard queries and render the dashboard in the terminal using Tamboui"

    private val id by option("--id", help = "Dashboard UUID")
    private val key by option("--key", "-k", help = "Stable dashboard key")
    private val parameters by option(
        "--param",
        "-p",
        help = "Shared query value as name=JSON; repeatable",
    ).multiple()

    override suspend fun execute(api: AnalyticsApi) {
        val dashboard = resolveDashboard(api, id, key)
        val inputs = parseExecutionParameters(parameters)
        val recordsByQuery = mutableMapOf<Uuid, List<JsonElement>>()
        val visualizations = dashboard.visualizations.mapNotNull { instance ->
            val visualization = instance.visualization
            if (visualization.type.name == "DATEPICKER") return@mapNotNull null
            val records = visualization.queryId?.let { queryId ->
                recordsByQuery.getOrPut(queryId) {
                    api.executeQuery(queryId, inputs).records
                }
            }.orEmpty()
            RenderedAnalyticsVisualization(
                name = visualization.name,
                description = visualization.description,
                type = visualization.type.name,
                configuration = visualization.configuration as? JsonObject,
                records = records,
                placement = (instance.configuration as? JsonObject).toGridPlacement(),
            )
        }
        TambouiAnalyticsRenderer.render(
            title = dashboard.name,
            visualizations = visualizations,
            output = ::echo,
        )
    }
}

class AnalyticsDashboardPermissionGrantCommand :
    AnalyticsDashboardPermissionCommand("grant", grant = true)

class AnalyticsDashboardPermissionRevokeCommand :
    AnalyticsDashboardPermissionCommand("revoke", grant = false)

abstract class AnalyticsDashboardPermissionCommand(
    name: String,
    private val grant: Boolean,
) : AnalyticsSubcommand(name) {
    override fun help(context: Context) =
        if (grant) "Grant a group permission on a dashboard"
        else "Revoke a group permission from a dashboard"

    private val id by option("--id", help = "Dashboard UUID").required()
    private val groupId by option("--group-id", help = "Security group UUID").required()
    private val action by option("--action", help = "Permission action").required()

    override suspend fun execute(api: AnalyticsApi) {
        val dashboardId = Uuid.parse(id)
        val group = Uuid.parse(groupId)
        val permission = enumValue<PermissionAction>(action)
        api.setDashboardPermission(dashboardId, group, permission, grant)
        echo(
            """{"${if (grant) "granted" else "revoked"}":true,"id":"$dashboardId","groupId":"$group","action":"${permission.name}"}""",
        )
    }
}

private suspend fun resolveDashboard(
    api: AnalyticsApi,
    id: String?,
    key: String?,
) = when {
    id != null -> api.getDashboard(Uuid.parse(id))
    key != null -> api.getDashboardByKey(key) ?: error("Analytics dashboard not found: $key")
    else -> error("Provide --id or --key")
}

private fun parseDashboardParameterArgument(value: String): AnalyticsQueryParameterInput =
    (parseJsonArgument(value, "parameter") as? JsonObject
        ?: error("Dashboard parameter definition must be a JSON object"))
        .toQueryParameterInput()

private fun parseVisualizationInstanceArgument(value: String): AnalyticsVisualizationInstanceInput =
    (parseJsonArgument(value, "visualization") as? JsonObject
        ?: error("Visualization placement must be a JSON object"))
        .toVisualizationInstanceInput()

private fun JsonObject?.toGridPlacement(): AnalyticsGridPlacement = AnalyticsGridPlacement(
    x = int("x") ?: 0,
    y = int("y") ?: 0,
    width = int("w") ?: 24,
    height = int("h") ?: 12,
)

private fun JsonObject?.int(key: String): Int? =
    (this?.get(key) as? JsonPrimitive)?.intOrNull
