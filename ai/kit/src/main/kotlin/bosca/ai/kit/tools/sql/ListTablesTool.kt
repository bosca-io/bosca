package bosca.ai.kit.tools.sql

import ai.koog.agents.core.tools.annotations.LLMDescription
import bosca.ai.kit.tools.KitTool
import bosca.ai.chat.model.AnalyticsInvestigationKind
import bosca.ai.kit.tools.analytics.InvestigationRecorder
import bosca.security.service.AuthenticationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

/** Lists the tables within a database schema. */
class ListTablesTool(
    private val sqlQuery: SqlQuery,
) : KitTool<ListTablesTool.Input, ListTablesTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "list_tables",
    description = "List tables in a database schema",
) {

    @Serializable
    data class Input(
        @property:LLMDescription("The catalog name")
        val catalog: String,
        @property:LLMDescription("The schema name")
        val schema: String,
    )

    @Serializable
    data class Output(val tables: List<String>)

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val recorder = currentCoroutineContext()[InvestigationRecorder]
        val startedAt = recorder?.startedAt()
        val tables = sqlQuery.listTables(input.catalog, input.schema)
        recorder?.record(AnalyticsInvestigationKind.DISCOVERY, descriptor.name, "${tables.size} tables", startedAt = checkNotNull(startedAt))
        return Output(tables = tables)
    }
}
