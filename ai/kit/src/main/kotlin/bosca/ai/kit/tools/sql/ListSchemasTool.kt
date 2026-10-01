package bosca.ai.kit.tools.sql

import ai.koog.agents.core.tools.annotations.LLMDescription
import bosca.ai.kit.tools.KitTool
import bosca.ai.chat.model.AnalyticsInvestigationKind
import bosca.ai.kit.tools.analytics.InvestigationRecorder
import bosca.security.service.AuthenticationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

/** Lists the schemas within a database catalog. */
class ListSchemasTool(
    private val sqlQuery: SqlQuery,
) : KitTool<ListSchemasTool.Input, ListSchemasTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "list_schemas",
    description = "List schemas in a database catalog",
) {

    @Serializable
    data class Input(
        @property:LLMDescription("The catalog name to list schemas from")
        val catalog: String,
    )

    @Serializable
    data class Output(val schemas: List<String>)

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val recorder = currentCoroutineContext()[InvestigationRecorder]
        val startedAt = recorder?.startedAt()
        val schemas = sqlQuery.listSchemas(input.catalog)
        recorder?.record(AnalyticsInvestigationKind.DISCOVERY, descriptor.name, "${schemas.size} schemas", startedAt = checkNotNull(startedAt))
        return Output(schemas = schemas)
    }
}
