package bosca.analytics.configuration

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

@Schemas
interface SchemaRegistrar {

    @Schema("analytics/analytics.graphqls")
    val analytics: String

    @Schema("analytics/visualizations.graphqls")
    val visualizations: String

    @Schema("analytics/dashboards.graphqls")
    val dashboards: String

    @Schema("analytics/error-groups.graphqls")
    val errorGroups: String

    @Schema("analytics/counters.graphqls")
    val counters: String

    @Schema("analytics/live-sessions.graphqls")
    val liveSessions: String

    @Schema("analytics/script-bindings.graphqls")
    val scriptBindings: String
}
