package bosca.system.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.design.model.ViewTab
import bosca.system.ui.graphql.*
import kotlinx.serialization.Serializable

@Serializable
data class PostgresConsoleModel(
    val admin: Boolean,
    var tab: String = "Overview",
    var overview: SystemGetPostgresAdminOverviewData.PostgresAdmin? = null,
    var tables: List<SystemGetPostgresTableStatsData.PostgresAdmin.TableStats> = emptyList(),
    var bloated: List<SystemGetPostgresBloatedTablesData.PostgresAdmin.BloatedTables> = emptyList(),
    var tableIO: List<SystemGetPostgresTableIOStatsData.PostgresAdmin.TableIOStats> = emptyList(),
    var indexes: List<SystemGetPostgresIndexStatsData.PostgresAdmin.IndexStats> = emptyList(),
    var unusedIndexes: List<SystemGetPostgresUnusedIndexesData.PostgresAdmin.UnusedIndexes> = emptyList(),
    var suggestions: List<SystemGetPostgresIndexSuggestionsData.PostgresAdmin.IndexSuggestions> = emptyList(),
    var slowQueries: List<SystemGetPostgresSlowQueriesData.PostgresAdmin.SlowQueries> = emptyList(),
    var settings: List<SystemGetPostgresSettingsData.PostgresAdmin.Settings> = emptyList(),
    var loadedTabs: Set<String> = emptySet(), var bloatedLoaded: Boolean = false, var ioLoaded: Boolean = false,
    var tableSchema: String = "", var indexSchema: String = "", var indexTab: String = "all",
    var queryLimit: String = "30", var queryOrder: String = "total", var minBloat: String = "5",
    var settingsFilter: String = "", var tableSelectionSchema: String = "", var tableSelectionName: String = "",
    var tableRequest: Long = 0, var indexSelectionSchema: String = "", var indexSelectionName: String = "",
    var indexRequest: Long = 0, var querySelection: Int = -1,
    var failedOperation: String = "", var completedOperation: String = "", var refreshes: Long = 0,
    val feedback: SystemFeedback = SystemFeedback(),
) {
    val tabs get() = listOf("Overview", "Cluster", "Tables", "Indexes", "Queries", "Locks", "Settings").map { ViewTab(it, it) }
    val indexTabs get() = listOf(ViewTab("all", "All indexes"), ViewTab("unused", "Unused indexes"), ViewTab("suggestions", "Suggestions"))
    val shownIndexes: List<ISystemPostgresIndexFields> get() = if (indexTab == "unused") unusedIndexes else indexes
    val selectedTable: ISystemPostgresTableFields? get() = tables.find { it.schemaName == tableSelectionSchema && it.tableName == tableSelectionName }
        ?: bloated.find { it.schemaName == tableSelectionSchema && it.tableName == tableSelectionName }
    val selectedIndex: ISystemPostgresIndexFields? get() = indexes.find { it.schemaName == indexSelectionSchema && it.indexName == indexSelectionName }
        ?: unusedIndexes.find { it.schemaName == indexSelectionSchema && it.indexName == indexSelectionName }
    val selectedQuery get() = slowQueries.getOrNull(querySelection)
    val tableOpenRequest get() = if (selectedTable == null) "" else "table-$tableRequest"
    val indexOpenRequest get() = if (selectedIndex == null) "" else "index-$indexRequest"

    private fun authorize() = require(admin) { "Administrator access is required." }
    private suspend fun overviewData(ctx: RenderContext) {
        overview = ctx.gql.execute(SystemGetPostgresAdminOverview, Unit).postgresAdmin
        refreshes++
    }
    private suspend fun tableData(ctx: RenderContext) {
        tables = ctx.gql.execute(SystemGetPostgresTableStats, SystemGetPostgresTableStats.Variables(tableSchema.takeIf(String::isNotBlank))).postgresAdmin.tableStats
        loadedTabs += "Tables"
    }
    private suspend fun indexData(ctx: RenderContext) {
        indexes = ctx.gql.execute(SystemGetPostgresIndexStats, SystemGetPostgresIndexStats.Variables(indexSchema.takeIf(String::isNotBlank))).postgresAdmin.indexStats
        unusedIndexes = ctx.gql.execute(SystemGetPostgresUnusedIndexes, Unit).postgresAdmin.unusedIndexes
        suggestions = ctx.gql.execute(SystemGetPostgresIndexSuggestions, Unit).postgresAdmin.indexSuggestions
        loadedTabs += "Indexes"
    }
    private suspend fun queryData(ctx: RenderContext) {
        val limit = queryLimit.toIntOrNull()
        require(limit != null && limit in 1..1000) { "Query limit must be between 1 and 1000." }
        require(queryOrder in setOf("total", "mean")) { "Choose total or mean query time." }
        slowQueries = ctx.gql.execute(SystemGetPostgresSlowQueries, SystemGetPostgresSlowQueries.Variables(limit, queryOrder)).postgresAdmin.slowQueries
        querySelection = -1; loadedTabs += "Queries"
    }
    private suspend fun settingsData(ctx: RenderContext) {
        settings = ctx.gql.execute(SystemGetPostgresSettings, SystemGetPostgresSettings.Variables(settingsFilter.takeIf(String::isNotBlank))).postgresAdmin.settings
        loadedTabs += "Settings"
    }

    suspend fun refresh(ctx: RenderContext) = feedback.perform { authorize(); overviewData(ctx) }
    suspend fun chooseTab(value: String, ctx: RenderContext) = feedback.perform {
        authorize(); require(tabs.any { it.value == value }) { "Choose an available PostgreSQL tab." }; tab = value
        if (value !in loadedTabs) when (value) {
            "Tables" -> tableData(ctx)
            "Indexes" -> indexData(ctx)
            "Queries" -> queryData(ctx)
            "Settings" -> settingsData(ctx)
        }
    }
    fun chooseIndexTab(value: String) { require(indexTabs.any { it.value == value }); indexTab = value }
    suspend fun loadTables(schema: String, ctx: RenderContext) = feedback.perform { authorize(); tableSchema = schema; tableData(ctx) }
    suspend fun loadIndexes(schema: String, ctx: RenderContext) = feedback.perform { authorize(); indexSchema = schema; indexData(ctx) }
    suspend fun loadBloated(minimum: String, ctx: RenderContext) = feedback.perform {
        authorize(); minBloat = minimum
        val ratio = minimum.toDoubleOrNull()
        require(ratio != null && ratio.isFinite() && ratio >= 0) { "Enter a non-negative bloat percentage." }
        bloated = ctx.gql.execute(SystemGetPostgresBloatedTables, SystemGetPostgresBloatedTables.Variables(ratio)).postgresAdmin.bloatedTables
        bloatedLoaded = true
    }
    suspend fun loadIO(schema: String, ctx: RenderContext) = feedback.perform {
        authorize(); tableSchema = schema
        tableIO = ctx.gql.execute(SystemGetPostgresTableIOStats, SystemGetPostgresTableIOStats.Variables(schema.takeIf(String::isNotBlank))).postgresAdmin.tableIOStats
        ioLoaded = true
    }
    suspend fun loadQueries(limit: String, order: String, ctx: RenderContext) = feedback.perform { authorize(); queryLimit = limit; queryOrder = order; queryData(ctx) }
    suspend fun loadSettings(filter: String, ctx: RenderContext) = feedback.perform { authorize(); settingsFilter = filter; settingsData(ctx) }
    fun openTable(schema: String, name: String) { tableSelectionSchema = schema; tableSelectionName = name; tableRequest++ }
    fun openIndex(schema: String, name: String) { indexSelectionSchema = schema; indexSelectionName = name; indexRequest++ }
    fun openQuery(index: Int) { querySelection = if (querySelection == index) -1 else index }

    suspend fun cancelQuery(pid: String, confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        failedOperation = "cancel:$pid"; completedOperation = ""; authorize(); require(confirmed) { "Confirm cancelling this query." }
        check(ctx.gql.execute(SystemCancelPgQuery, SystemCancelPgQuery.Variables(pid.toInt())).postgresAdmin.cancelQuery == true) { "PostgreSQL did not cancel the query." }
        completedOperation = failedOperation; failedOperation = ""; feedback.message = "Cancel signal sent to PID $pid."
        overviewData(ctx)
    }
    suspend fun terminateBackend(pid: String, confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        failedOperation = "terminate:$pid"; completedOperation = ""; authorize(); require(confirmed) { "Confirm terminating this backend." }
        check(ctx.gql.execute(SystemTerminatePgBackend, SystemTerminatePgBackend.Variables(pid.toInt())).postgresAdmin.terminateBackend == true) { "PostgreSQL did not terminate the backend." }
        completedOperation = failedOperation; failedOperation = ""; feedback.message = "Terminate signal sent to PID $pid."
        overviewData(ctx)
    }
    suspend fun analyzeTable(schema: String, name: String, confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        tableRequest++
        failedOperation = "analyze:$schema.$name"; completedOperation = ""; authorize(); require(confirmed) { "Confirm analyzing this table." }
        check(ctx.gql.execute(SystemAnalyzePgTable, SystemAnalyzePgTable.Variables(schema, name)).postgresAdmin.analyzeTable == true) { "PostgreSQL did not analyze the table." }
        completedOperation = failedOperation; failedOperation = ""; feedback.message = "ANALYZE completed on $schema.$name."
        tableData(ctx)
    }
    suspend fun resetStatements(confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        failedOperation = "reset"; completedOperation = ""; authorize(); require(confirmed) { "Confirm resetting statement statistics." }
        check(ctx.gql.execute(SystemResetPgStatStatements, Unit).postgresAdmin.resetStatStatements == true) { "Statement statistics could not be reset. Check that pg_stat_statements is installed." }
        completedOperation = "reset"; failedOperation = ""; feedback.message = "Statement statistics reset."
        queryData(ctx)
    }
}

suspend fun postgresConsole(): PostgresConsoleModel {
    val ctx = currentRenderContext()
    return PostgresConsoleModel(systemAdministrator(ctx)).also { if (it.admin) it.refresh(ctx) }
}
