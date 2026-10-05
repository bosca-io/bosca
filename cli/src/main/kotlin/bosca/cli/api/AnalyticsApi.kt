package bosca.cli.api

import bosca.graphql.client.execute
import bosca.graphql.gen.AddAnalyticsDashboard
import bosca.graphql.gen.AddAnalyticsDashboardPermission
import bosca.graphql.gen.AddAnalyticsDashboardVisualization
import bosca.graphql.gen.AddAnalyticsQuery
import bosca.graphql.gen.AddAnalyticsQueryPermission
import bosca.graphql.gen.AddAnalyticsVisualization
import bosca.graphql.gen.AddAnalyticsVisualizationPermission
import bosca.graphql.gen.AnalyticsDashboardInput
import bosca.graphql.gen.AnalyticsQueryExecutionParameterInput
import bosca.graphql.gen.AnalyticsQueryInput
import bosca.graphql.gen.AnalyticsVisualizationInput
import bosca.graphql.gen.DeleteAnalyticsDashboard
import bosca.graphql.gen.DeleteAnalyticsDashboardPermission
import bosca.graphql.gen.DeleteAnalyticsQuery
import bosca.graphql.gen.DeleteAnalyticsQueryPermission
import bosca.graphql.gen.DeleteAnalyticsVisualization
import bosca.graphql.gen.DeleteAnalyticsVisualizationPermission
import bosca.graphql.gen.EditAnalyticsDashboard
import bosca.graphql.gen.EditAnalyticsQuery
import bosca.graphql.gen.EditAnalyticsVisualization
import bosca.graphql.gen.ExecuteAnalyticsQuery
import bosca.graphql.gen.ExecuteAnalyticsQueryByKey
import bosca.graphql.gen.GetAnalyticsDashboardById
import bosca.graphql.gen.GetAnalyticsDashboardByKey
import bosca.graphql.gen.GetAnalyticsQueryById
import bosca.graphql.gen.GetAnalyticsQueryByKey
import bosca.graphql.gen.GetAnalyticsVisualizationById
import bosca.graphql.gen.GetAnalyticsVisualizationByKey
import bosca.graphql.gen.IAnalyticsDashboardFragment
import bosca.graphql.gen.IAnalyticsDashboardSummaryFragment
import bosca.graphql.gen.IAnalyticsQueryFragment
import bosca.graphql.gen.IAnalyticsQuerySummaryFragment
import bosca.graphql.gen.IAnalyticsVisualizationFragment
import bosca.graphql.gen.IAnalyticsVisualizationSummaryFragment
import bosca.graphql.gen.ListAnalyticsDashboards
import bosca.graphql.gen.ListAnalyticsQueries
import bosca.graphql.gen.ListAnalyticsVisualizations
import bosca.graphql.gen.PermissionAction
import bosca.graphql.gen.PermissionInput
import bosca.graphql.gen.RemoveAnalyticsDashboardVisualization
import bosca.graphql.gen.RefreshAnalyticsQuery
import java.time.ZonedDateTime
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.Uuid

data class AnalyticsExecutionResult(
    val records: List<JsonElement>,
    val cached: Boolean,
    val refreshedAt: ZonedDateTime?,
)

/**
 * Typed GraphQL client for analytics queries, visualizations, and dashboards.
 *
 * The CLI and embedded MCP server share this client so authentication,
 * permission enforcement, generated serializers, and error behavior stay
 * identical across both surfaces.
 */
class AnalyticsApi(network: NetworkClient) : Api(network) {

    private val gql = network.boscaGraphql

    suspend fun listQueries(): List<IAnalyticsQuerySummaryFragment> =
        gql.execute(ListAnalyticsQueries, Unit).analytics.queries.all

    suspend fun getQuery(id: Uuid): IAnalyticsQueryFragment =
        gql.execute(GetAnalyticsQueryById, GetAnalyticsQueryById.Variables(id))
            .analytics.queries.queryById

    suspend fun getQueryByKey(key: String): IAnalyticsQueryFragment? =
        gql.execute(GetAnalyticsQueryByKey, GetAnalyticsQueryByKey.Variables(key))
            .analytics.queries.queryByKey

    suspend fun executeQuery(
        id: Uuid,
        parameters: List<AnalyticsQueryExecutionParameterInput>,
    ): AnalyticsExecutionResult {
        val result = gql.execute(ExecuteAnalyticsQuery, ExecuteAnalyticsQuery.Variables(id, parameters))
            .analytics.queries.execute
        return AnalyticsExecutionResult(result.records, result.cached, result.refreshedAt)
    }

    suspend fun executeQueryByKey(
        key: String,
        parameters: List<AnalyticsQueryExecutionParameterInput>,
    ): AnalyticsExecutionResult {
        val result = gql.execute(ExecuteAnalyticsQueryByKey, ExecuteAnalyticsQueryByKey.Variables(key, parameters))
            .analytics.queries.executeByKey
        return AnalyticsExecutionResult(result.records, result.cached, result.refreshedAt)
    }

    suspend fun createQuery(input: AnalyticsQueryInput): IAnalyticsQueryFragment =
        gql.execute(AddAnalyticsQuery, AddAnalyticsQuery.Variables(input))
            .analytics.queries.add

    suspend fun updateQuery(input: AnalyticsQueryInput): IAnalyticsQueryFragment =
        gql.execute(EditAnalyticsQuery, EditAnalyticsQuery.Variables(input))
            .analytics.queries.edit

    suspend fun deleteQuery(id: Uuid): Boolean =
        gql.execute(DeleteAnalyticsQuery, DeleteAnalyticsQuery.Variables(id))
            .analytics.queries.delete

    suspend fun refreshQuery(id: Uuid): Boolean =
        gql.execute(RefreshAnalyticsQuery, RefreshAnalyticsQuery.Variables(id))
            .analytics.queries.refresh

    suspend fun setQueryPermission(id: Uuid, groupId: Uuid, action: PermissionAction, grant: Boolean) {
        val input = PermissionInput(action, id.toString(), groupId.toString())
        if (grant) {
            gql.execute(AddAnalyticsQueryPermission, AddAnalyticsQueryPermission.Variables(input))
        } else {
            gql.execute(DeleteAnalyticsQueryPermission, DeleteAnalyticsQueryPermission.Variables(input))
        }
    }

    suspend fun listVisualizations(): List<IAnalyticsVisualizationSummaryFragment> =
        gql.execute(ListAnalyticsVisualizations, Unit).analytics.visualizations.all

    suspend fun getVisualization(id: Uuid): IAnalyticsVisualizationFragment =
        gql.execute(GetAnalyticsVisualizationById, GetAnalyticsVisualizationById.Variables(id))
            .analytics.visualizations.byId

    suspend fun getVisualizationByKey(key: String): IAnalyticsVisualizationFragment? =
        gql.execute(GetAnalyticsVisualizationByKey, GetAnalyticsVisualizationByKey.Variables(key))
            .analytics.visualizations.byKey

    suspend fun createVisualization(input: AnalyticsVisualizationInput): IAnalyticsVisualizationFragment =
        gql.execute(AddAnalyticsVisualization, AddAnalyticsVisualization.Variables(input))
            .analytics.visualizations.add

    suspend fun updateVisualization(input: AnalyticsVisualizationInput): IAnalyticsVisualizationFragment =
        gql.execute(EditAnalyticsVisualization, EditAnalyticsVisualization.Variables(input))
            .analytics.visualizations.edit

    suspend fun deleteVisualization(id: Uuid): Boolean =
        gql.execute(DeleteAnalyticsVisualization, DeleteAnalyticsVisualization.Variables(id))
            .analytics.visualizations.delete

    suspend fun setVisualizationPermission(id: Uuid, groupId: Uuid, action: PermissionAction, grant: Boolean) {
        val input = PermissionInput(action, id.toString(), groupId.toString())
        if (grant) {
            gql.execute(AddAnalyticsVisualizationPermission, AddAnalyticsVisualizationPermission.Variables(input))
        } else {
            gql.execute(DeleteAnalyticsVisualizationPermission, DeleteAnalyticsVisualizationPermission.Variables(input))
        }
    }

    suspend fun listDashboards(): List<IAnalyticsDashboardSummaryFragment> =
        gql.execute(ListAnalyticsDashboards, Unit).analytics.dashboards.all

    suspend fun getDashboard(id: Uuid): IAnalyticsDashboardFragment =
        gql.execute(GetAnalyticsDashboardById, GetAnalyticsDashboardById.Variables(id))
            .analytics.dashboards.byId

    suspend fun getDashboardByKey(key: String): IAnalyticsDashboardFragment? =
        gql.execute(GetAnalyticsDashboardByKey, GetAnalyticsDashboardByKey.Variables(key))
            .analytics.dashboards.byKey

    suspend fun createDashboard(input: AnalyticsDashboardInput): IAnalyticsDashboardFragment =
        gql.execute(AddAnalyticsDashboard, AddAnalyticsDashboard.Variables(input))
            .analytics.dashboards.add

    suspend fun updateDashboard(input: AnalyticsDashboardInput): IAnalyticsDashboardFragment =
        gql.execute(EditAnalyticsDashboard, EditAnalyticsDashboard.Variables(input))
            .analytics.dashboards.edit

    suspend fun deleteDashboard(id: Uuid): Boolean =
        gql.execute(DeleteAnalyticsDashboard, DeleteAnalyticsDashboard.Variables(id))
            .analytics.dashboards.delete

    suspend fun setDashboardPermission(id: Uuid, groupId: Uuid, action: PermissionAction, grant: Boolean) {
        val input = PermissionInput(action, id.toString(), groupId.toString())
        if (grant) {
            gql.execute(AddAnalyticsDashboardPermission, AddAnalyticsDashboardPermission.Variables(input))
        } else {
            gql.execute(DeleteAnalyticsDashboardPermission, DeleteAnalyticsDashboardPermission.Variables(input))
        }
    }

    suspend fun addDashboardVisualization(
        dashboardId: Uuid,
        visualizationId: Uuid,
        configuration: JsonElement?,
    ): Uuid =
        gql.execute(
            AddAnalyticsDashboardVisualization,
            AddAnalyticsDashboardVisualization.Variables(dashboardId, visualizationId, configuration),
        ).analytics.dashboards.addVisualization

    suspend fun removeDashboardVisualization(instanceId: Uuid): Boolean =
        gql.execute(
            RemoveAnalyticsDashboardVisualization,
            RemoveAnalyticsDashboardVisualization.Variables(instanceId),
        ).analytics.dashboards.removeVisualization
}
