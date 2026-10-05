package bosca.ai.kit.tools.sql

import ai.koog.agents.core.tools.annotations.LLMDescription
import bosca.ai.kit.tools.KitTool
import bosca.ai.chat.model.AnalyticsInvestigationKind
import bosca.ai.kit.tools.analytics.InvestigationRecorder
import bosca.security.service.AuthenticationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/** Describes the columns and types of a database table. */
class DescribeTableTool(
    private val sqlQuery: SqlQuery,
) : KitTool<DescribeTableTool.Input, DescribeTableTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "describe_table",
    description = "Describe the columns and types of a database table",
) {

    @Serializable
    data class Input(
        @property:LLMDescription("The catalog name")
        val catalog: String,
        @property:LLMDescription("The schema name")
        val schema: String,
        @property:LLMDescription("The table name")
        val table: String,
    )

    @Serializable
    data class Output(val columns: List<Map<String, String?>> = emptyList(), val error: String? = null)

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val recorder = currentCoroutineContext()[InvestigationRecorder]
        val startedAt = recorder?.startedAt()
        return try {
            val columns = sqlQuery.describeTable(input.catalog, input.schema, input.table)
            recorder?.record(AnalyticsInvestigationKind.DISCOVERY, descriptor.name, "${columns.size} columns", startedAt = checkNotNull(startedAt))
            Output(columns = columns)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Error describing table", e)
            recorder?.record(AnalyticsInvestigationKind.DISCOVERY, descriptor.name, "error: ${e.message ?: "unknown error"}", startedAt = checkNotNull(startedAt))
            Output(error = e.message ?: "Unknown error")
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(DescribeTableTool::class.java)
    }
}
