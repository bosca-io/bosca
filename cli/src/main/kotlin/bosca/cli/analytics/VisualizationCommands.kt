package bosca.cli.analytics

import bosca.cli.BoscaCliCommand
import bosca.cli.api.AnalyticsApi
import bosca.graphql.gen.AnalyticsVisualizationInput
import bosca.graphql.gen.AnalyticsVisualizationType
import bosca.graphql.gen.PermissionAction
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

class AnalyticsVisualizationListCommand : AnalyticsSubcommand(name = "list") {
    override fun help(context: Context) = "List saved analytics visualizations"

    private val jsonOutput by option("--json", help = "Emit structured JSON").flag()
    private val limit by option("--limit", help = "Maximum visualizations to display").int().default(50)

    override suspend fun execute(api: AnalyticsApi) {
        val visualizations = api.listVisualizations().take(limit.coerceIn(1, 1_000))
        if (jsonOutput) {
            echo(
                analyticsJson.encodeToString(
                    JsonArray.serializer(),
                    JsonArray(visualizations.map { it.toJson() }),
                ),
            )
            return
        }
        if (visualizations.isEmpty()) {
            echo("No analytics visualizations found.")
            return
        }
        echo("%-36s  %-22s  %-26s  %-16s  %s".format("ID", "KEY", "NAME", "TYPE", "QUERY ID"))
        echo("-".repeat(125))
        visualizations.forEach { visualization ->
            echo(
                "%-36s  %-22s  %-26s  %-16s  %s".format(
                    visualization.id,
                    visualization.key.take(22),
                    visualization.name.take(26),
                    visualization.type.name,
                    visualization.queryId ?: "-",
                ),
            )
        }
    }
}

class AnalyticsVisualizationGetCommand : AnalyticsSubcommand(name = "get") {
    override fun help(context: Context) = "Get a saved analytics visualization by ID or key"

    private val id by option("--id", help = "Visualization UUID")
    private val key by option("--key", "-k", help = "Stable visualization key")

    override suspend fun execute(api: AnalyticsApi) {
        val visualization = resolveVisualization(api, id, key)
        echo(analyticsJson.encodeToString(JsonElement.serializer(), visualization.toJson()))
    }
}

class AnalyticsVisualizationCreateCommand : AnalyticsSubcommand(name = "create") {
    override fun help(context: Context) = "Create a saved analytics visualization"

    private val key by option("--key", "-k", help = "Stable visualization key").required()
    private val name by option("--name", "-n", help = "Display name").required()
    private val description by option("--description", "-d", help = "Description").required()
    private val type by option("--type", help = "Visualization type").required()
    private val queryId by option("--query-id", help = "Saved query UUID")
    private val configurationJson by option(
        "--configuration-json",
        help = "Visualization configuration JSON object. Supports @file.",
    )

    override suspend fun execute(api: AnalyticsApi) {
        val created = api.createVisualization(
            AnalyticsVisualizationInput(
                id = null,
                key = key,
                name = name,
                description = description,
                queryId = queryId?.let(Uuid::parse),
                type = enumValue<AnalyticsVisualizationType>(type),
                configuration = parseJsonObjectArgument(configurationJson, "configuration"),
            ),
        )
        echo(analyticsJson.encodeToString(JsonElement.serializer(), created.toJson()))
    }
}

class AnalyticsVisualizationUpdateCommand : AnalyticsSubcommand(name = "update") {
    override fun help(context: Context) =
        "Update a saved analytics visualization; unspecified fields are preserved"

    private val id by option("--id", help = "Visualization UUID").required()
    private val key by option("--key", "-k", help = "New stable visualization key")
    private val name by option("--name", "-n", help = "New display name")
    private val description by option("--description", "-d", help = "New description")
    private val type by option("--type", help = "New visualization type")
    private val queryId by option("--query-id", help = "New saved query UUID")
    private val clearQueryId by option("--clear-query-id").flag()
    private val configurationJson by option(
        "--configuration-json",
        help = "Replacement configuration JSON object. Supports @file.",
    )
    private val clearConfiguration by option("--clear-configuration").flag()

    override suspend fun execute(api: AnalyticsApi) {
        require(!(queryId != null && clearQueryId)) {
            "Use only one of --query-id or --clear-query-id"
        }
        require(!(configurationJson != null && clearConfiguration)) {
            "Use only one of --configuration-json or --clear-configuration"
        }
        val visualizationId = Uuid.parse(id)
        val current = api.getVisualization(visualizationId)
        val requestedQueryId = queryId
        val updated = api.updateVisualization(
            AnalyticsVisualizationInput(
                id = visualizationId,
                key = key ?: current.key,
                name = name ?: current.name,
                description = description ?: current.description,
                queryId = when {
                    clearQueryId -> null
                    requestedQueryId != null -> Uuid.parse(requestedQueryId)
                    else -> current.queryId
                },
                type = type?.let { enumValue(it) } ?: current.type,
                configuration = when {
                    clearConfiguration -> null
                    configurationJson != null ->
                        parseJsonObjectArgument(configurationJson, "configuration")
                    else -> current.configuration
                },
            ),
        )
        echo(analyticsJson.encodeToString(JsonElement.serializer(), updated.toJson()))
    }
}

class AnalyticsVisualizationDeleteCommand : AnalyticsSubcommand(name = "delete") {
    override fun help(context: Context) = "Delete a saved analytics visualization"

    private val id by option("--id", help = "Visualization UUID").required()

    override suspend fun execute(api: AnalyticsApi) {
        val visualizationId = Uuid.parse(id)
        require(api.deleteVisualization(visualizationId)) { "The analytics visualization was not deleted" }
        echo("""{"deleted":true,"id":"$visualizationId"}""")
    }
}

class AnalyticsVisualizationRenderCommand : AnalyticsSubcommand(name = "render") {
    override fun help(context: Context) =
        "Execute and render a saved visualization in the terminal using Tamboui"

    private val id by option("--id", help = "Visualization UUID")
    private val key by option("--key", "-k", help = "Stable visualization key")
    private val parameters by option(
        "--param",
        "-p",
        help = "Named query value as name=JSON; repeatable",
    ).multiple()

    override suspend fun execute(api: AnalyticsApi) {
        val visualization = resolveVisualization(api, id, key)
        val records = visualization.queryId?.let { queryId ->
            api.executeQuery(queryId, parseExecutionParameters(parameters)).records
        }.orEmpty()
        TambouiAnalyticsRenderer.render(
            title = visualization.name,
            visualizations = listOf(
                RenderedAnalyticsVisualization(
                    name = visualization.name,
                    description = visualization.description,
                    type = visualization.type.name,
                    configuration = visualization.configuration as? JsonObject,
                    records = records,
                ),
            ),
            output = ::echo,
        )
    }
}

class AnalyticsVisualizationPermissionGrantCommand :
    AnalyticsVisualizationPermissionCommand("grant", grant = true)

class AnalyticsVisualizationPermissionRevokeCommand :
    AnalyticsVisualizationPermissionCommand("revoke", grant = false)

abstract class AnalyticsVisualizationPermissionCommand(
    name: String,
    private val grant: Boolean,
) : AnalyticsSubcommand(name) {
    override fun help(context: Context) =
        if (grant) "Grant a group permission on a visualization"
        else "Revoke a group permission from a visualization"

    private val id by option("--id", help = "Visualization UUID").required()
    private val groupId by option("--group-id", help = "Security group UUID").required()
    private val action by option("--action", help = "Permission action").required()

    override suspend fun execute(api: AnalyticsApi) {
        val visualizationId = Uuid.parse(id)
        val group = Uuid.parse(groupId)
        val permission = enumValue<PermissionAction>(action)
        api.setVisualizationPermission(visualizationId, group, permission, grant)
        echo(
            """{"${if (grant) "granted" else "revoked"}":true,"id":"$visualizationId","groupId":"$group","action":"${permission.name}"}""",
        )
    }
}

class AnalyticsSampleCommand : BoscaCliCommand(name = "sample") {
    override fun help(context: Context) =
        "Render sample number, bar, line, pie, and table visualizations using Tamboui"

    override fun run() = runBlocking {
        TambouiAnalyticsRenderer.render(
            title = "Bosca analytics visualization samples",
            visualizations = sampleAnalyticsVisualizations(),
            output = ::echo,
        )
    }
}

private suspend fun resolveVisualization(
    api: AnalyticsApi,
    id: String?,
    key: String?,
) = when {
    id != null -> api.getVisualization(Uuid.parse(id))
    key != null -> api.getVisualizationByKey(key) ?: error("Analytics visualization not found: $key")
    else -> error("Provide --id or --key")
}
