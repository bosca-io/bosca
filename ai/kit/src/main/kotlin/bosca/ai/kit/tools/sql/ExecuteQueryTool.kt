package bosca.ai.kit.tools.sql

import ai.koog.agents.core.tools.annotations.LLMDescription
import bosca.ai.kit.tools.KitTool
import bosca.ai.kit.tools.analytics.InvestigationRecorder
import bosca.ai.chat.model.AnalyticsInvestigationKind
import bosca.security.service.AuthenticationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

/** Executes a read-only SELECT query against the analytics database (max 10,000 rows). */
class ExecuteQueryTool(
    private val sqlQuery: SqlQuery,
) : KitTool<ExecuteQueryTool.Input, ExecuteQueryTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "execute_query",
    description = "Execute a read-only SQL query against the analytics database",
) {

    @Serializable
    data class Input(
        @property:LLMDescription("The SQL query to execute. Must be a SELECT query.")
        val sql: String,
    )

    @Serializable
    data class Output(
        val rows: List<Map<String, String?>>,
        val rowCount: Int,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val recorder = currentCoroutineContext()[InvestigationRecorder]
        val startedAt = recorder?.startedAt()
        val startedNanos = System.nanoTime()
        val results = sqlQuery.executeQuery(input.sql, MAX_ROWS)
        recorder?.record(
            kind = AnalyticsInvestigationKind.QUERY,
            tool = descriptor.name,
            sql = input.sql,
            resultSummary = "${results.size} rows in ${(System.nanoTime() - startedNanos) / 1_000_000} ms",
            startedAt = checkNotNull(startedAt),
        )
        return Output(
            rows = results.map { row -> row.mapValues { it.value?.toString() } },
            rowCount = results.size,
        )
    }

    private companion object {
        const val MAX_ROWS = 10000
    }
}
