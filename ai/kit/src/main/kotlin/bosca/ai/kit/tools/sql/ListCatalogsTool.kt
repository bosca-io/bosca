package bosca.ai.kit.tools.sql

import bosca.ai.kit.tools.KitTool
import bosca.ai.chat.model.AnalyticsInvestigationKind
import bosca.ai.kit.tools.analytics.InvestigationRecorder
import bosca.security.service.AuthenticationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

/** Lists the analytics database catalogs available to query. */
class ListCatalogsTool(
    private val sqlQuery: SqlQuery,
) : KitTool<ListCatalogsTool.Args, ListCatalogsTool.Output>(
    Args.serializer(),
    Output.serializer(),
    name = "list_catalogs",
    description = "List all available database catalogs",
) {

    @Serializable
    class Args

    @Serializable
    data class Output(val catalogs: List<String>)

    override suspend fun execute(authentication: AuthenticationContext, input: Args): Output {
        val recorder = currentCoroutineContext()[InvestigationRecorder]
        val startedAt = recorder?.startedAt()
        val catalogs = sqlQuery.listCatalogs()
        recorder?.record(AnalyticsInvestigationKind.DISCOVERY, descriptor.name, "${catalogs.size} catalogs", startedAt = checkNotNull(startedAt))
        return Output(catalogs = catalogs)
    }
}
