package bosca.cli.analytics

import bosca.cli.api.AnalyticsApi
import bosca.graphql.gen.AnalyticsQueryInput
import bosca.graphql.gen.AnalyticsQueryParameterInput
import bosca.graphql.gen.PermissionAction
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

class AnalyticsQueryListCommand : AnalyticsSubcommand(name = "list") {
    override fun help(context: Context) = "List saved analytics queries"

    private val jsonOutput by option("--json", help = "Emit structured JSON").flag()
    private val limit by option("--limit", help = "Maximum queries to display").int().default(50)

    override suspend fun execute(api: AnalyticsApi) {
        val queries = api.listQueries().take(limit.coerceIn(1, 1_000))
        if (jsonOutput) {
            echo(analyticsJson.encodeToString(JsonArray.serializer(), JsonArray(queries.map { it.toJson() })))
            return
        }
        if (queries.isEmpty()) {
            echo("No analytics queries found.")
            return
        }
        echo("%-36s  %-24s  %-28s  %s".format("ID", "KEY", "NAME", "REFRESH"))
        echo("-".repeat(105))
        queries.forEach { query ->
            echo(
                "%-36s  %-24s  %-28s  %s".format(
                    query.id,
                    query.key.take(24),
                    query.name.take(28),
                    query.refreshIntervalSeconds?.let { "${it}s" } ?: "-",
                ),
            )
        }
    }
}

class AnalyticsQueryGetCommand : AnalyticsSubcommand(name = "get") {
    override fun help(context: Context) = "Get a saved analytics query by ID or key"

    private val id by option("--id", help = "Query UUID")
    private val key by option("--key", "-k", help = "Stable query key")

    override suspend fun execute(api: AnalyticsApi) {
        val queryId = id
        val queryKey = key
        val query = when {
            queryId != null -> api.getQuery(Uuid.parse(queryId))
            queryKey != null ->
                api.getQueryByKey(queryKey) ?: error("Analytics query not found: $queryKey")
            else -> error("Provide --id or --key")
        }
        echo(analyticsJson.encodeToString(JsonElement.serializer(), query.toJson()))
    }
}

class AnalyticsQueryCreateCommand : AnalyticsSubcommand(name = "create") {
    override fun help(context: Context) = "Create a saved analytics query"

    private val key by option("--key", "-k", help = "Stable query key").required()
    private val name by option("--name", "-n", help = "Display name").required()
    private val description by option("--description", "-d", help = "Description").required()
    private val query by option("--query", help = "SQL query text")
    private val queryFile by option("--query-file", help = "Read SQL query text from a file")
    private val parameterJson by option(
        "--parameter-json",
        help = "Parameter definition JSON object; repeatable. Supports @file.",
    ).multiple()
    private val configurationJson by option(
        "--configuration-json",
        help = "Query configuration JSON object. Supports @file.",
    )
    private val refreshInterval by option(
        "--refresh-interval",
        help = "Cached-result refresh interval in seconds",
    ).int()

    override suspend fun execute(api: AnalyticsApi) {
        val created = api.createQuery(
            AnalyticsQueryInput(
                id = null,
                key = key,
                name = name,
                description = description,
                query = resolveQueryText(query, queryFile),
                parameters = parameterJson.map(::parseQueryParameterArgument),
                configuration = parseJsonObjectArgument(configurationJson, "configuration"),
                refreshIntervalSeconds = refreshInterval,
            ),
        )
        echo(analyticsJson.encodeToString(JsonElement.serializer(), created.toJson()))
    }
}

class AnalyticsQueryUpdateCommand : AnalyticsSubcommand(name = "update") {
    override fun help(context: Context) = "Update a saved analytics query; unspecified fields are preserved"

    private val id by option("--id", help = "Query UUID").required()
    private val key by option("--key", "-k", help = "New stable query key")
    private val name by option("--name", "-n", help = "New display name")
    private val description by option("--description", "-d", help = "New description")
    private val query by option("--query", help = "New SQL query text")
    private val queryFile by option("--query-file", help = "Read new SQL query text from a file")
    private val parameterJson by option(
        "--parameter-json",
        help = "Replacement parameter definition JSON object; repeatable. Supports @file.",
    ).multiple()
    private val clearParameters by option("--clear-parameters").flag()
    private val configurationJson by option(
        "--configuration-json",
        help = "Replacement query configuration JSON object. Supports @file.",
    )
    private val clearConfiguration by option("--clear-configuration").flag()
    private val refreshInterval by option("--refresh-interval").int()
    private val clearRefreshInterval by option("--clear-refresh-interval").flag()

    override suspend fun execute(api: AnalyticsApi) {
        require(!(query != null && queryFile != null)) { "Use only one of --query or --query-file" }
        require(!(configurationJson != null && clearConfiguration)) {
            "Use only one of --configuration-json or --clear-configuration"
        }
        require(!(parameterJson.isNotEmpty() && clearParameters)) {
            "Use only one of --parameter-json or --clear-parameters"
        }
        require(!(refreshInterval != null && clearRefreshInterval)) {
            "Use only one of --refresh-interval or --clear-refresh-interval"
        }
        val queryId = Uuid.parse(id)
        val current = api.getQuery(queryId)
        val updated = api.updateQuery(
            AnalyticsQueryInput(
                id = queryId,
                key = key ?: current.key,
                name = name ?: current.name,
                description = description ?: current.description,
                query = when {
                    query != null || queryFile != null -> resolveQueryText(query, queryFile)
                    else -> current.query ?: error("Existing query text is unavailable")
                },
                parameters = when {
                    clearParameters -> emptyList()
                    parameterJson.isNotEmpty() ->
                        parameterJson.map(::parseQueryParameterArgument)
                    else ->
                        current.parameters.sortedBy { it.sort }.map { parameter ->
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
                configuration = when {
                    clearConfiguration -> null
                    configurationJson != null ->
                        parseJsonObjectArgument(configurationJson, "configuration")
                    else -> current.configuration
                },
                refreshIntervalSeconds = when {
                    clearRefreshInterval -> null
                    refreshInterval != null -> refreshInterval
                    else -> current.refreshIntervalSeconds
                },
            ),
        )
        echo(analyticsJson.encodeToString(JsonElement.serializer(), updated.toJson()))
    }
}

class AnalyticsQueryDeleteCommand : AnalyticsSubcommand(name = "delete") {
    override fun help(context: Context) = "Delete a saved analytics query"

    private val id by option("--id", help = "Query UUID").required()

    override suspend fun execute(api: AnalyticsApi) {
        val queryId = Uuid.parse(id)
        require(api.deleteQuery(queryId)) { "The analytics query was not deleted" }
        echo("""{"deleted":true,"id":"$queryId"}""")
    }
}

class AnalyticsQueryRefreshCommand : AnalyticsSubcommand(name = "refresh") {
    override fun help(context: Context) = "Refresh cached results for a saved analytics query"

    private val id by option("--id", help = "Query UUID")
    private val key by option("--key", "-k", help = "Stable query key")

    override suspend fun execute(api: AnalyticsApi) {
        val queryKey = key
        require((id == null) xor (queryKey == null)) { "Provide exactly one of --id or --key" }
        val queryId = id?.let(Uuid::parse)
            ?: queryKey?.let { api.getQueryByKey(it)?.id }
            ?: error("Analytics query not found: $queryKey")
        echo("""{"enqueued":${api.refreshQuery(queryId)},"id":"$queryId"}""")
    }
}

class AnalyticsQueryExecuteCommand : AnalyticsSubcommand(name = "execute") {
    override fun help(context: Context) = "Execute a saved analytics query by ID or key"

    private val id by option("--id", help = "Query UUID")
    private val key by option("--key", "-k", help = "Stable query key")
    private val parameters by option(
        "--param",
        "-p",
        help = "Named query value as name=JSON; unquoted values are strings. Repeatable.",
    ).multiple()
    private val format by option("--format", help = "Output format")
        .choice("json", "table")
        .default("table")
    private val maxRecords by option("--max-records", help = "Maximum records to print")
        .int()
        .default(100)

    override suspend fun execute(api: AnalyticsApi) {
        val queryKey = key
        require((id == null) xor (queryKey == null)) { "Provide exactly one of --id or --key" }
        val inputs = parseExecutionParameters(parameters)
        val queryId = id
        val result = if (queryId != null) {
            api.executeQuery(Uuid.parse(queryId), inputs)
        } else {
            api.executeQueryByKey(requireNotNull(queryKey), inputs)
        }
        val limit = maxRecords.coerceIn(1, 10_000)
        if (format == "json") {
            echo(
                analyticsJson.encodeToString(
                    JsonElement.serializer(),
                    recordsJson(result.records, result.cached, result.refreshedAt?.toString(), limit),
                ),
            )
        } else {
            tableLines(result.records, limit).forEach(::echo)
            if (result.cached) {
                echo("Cached result${result.refreshedAt?.let { " refreshed at $it" }.orEmpty()}")
            }
        }
    }
}

class AnalyticsQueryPermissionGrantCommand : AnalyticsQueryPermissionCommand("grant", grant = true)

class AnalyticsQueryPermissionRevokeCommand : AnalyticsQueryPermissionCommand("revoke", grant = false)

abstract class AnalyticsQueryPermissionCommand(
    name: String,
    private val grant: Boolean,
) : AnalyticsSubcommand(name) {
    override fun help(context: Context) =
        if (grant) "Grant a group permission on a saved query" else "Revoke a group permission from a saved query"

    private val id by option("--id", help = "Query UUID").required()
    private val groupId by option("--group-id", help = "Security group UUID").required()
    private val action by option("--action", help = "Permission action").required()

    override suspend fun execute(api: AnalyticsApi) {
        val queryId = Uuid.parse(id)
        val group = Uuid.parse(groupId)
        val permission = enumValue<PermissionAction>(action)
        api.setQueryPermission(queryId, group, permission, grant)
        echo(
            """{"${if (grant) "granted" else "revoked"}":true,"id":"$queryId","groupId":"$group","action":"${permission.name}"}""",
        )
    }
}

private fun resolveQueryText(query: String?, queryFile: String?): String {
    require((query == null) xor (queryFile == null)) { "Provide exactly one of --query or --query-file" }
    return query ?: File(requireNotNull(queryFile)).readText()
}

private fun parseQueryParameterArgument(value: String): AnalyticsQueryParameterInput =
    (parseJsonArgument(value, "parameter") as? JsonObject
        ?: error("Parameter definition must be a JSON object"))
        .toQueryParameterInput()
