package bosca.analytics.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

@DbMapper(AnalyticsVisualizationTypeMapper::class)
@Serializable
enum class AnalyticsVisualizationType {
    NUMBER,
    BAR,
    LINE,
    PIE,
    DOUGHNUT,
    BUBBLE,
    SCATTER,
    TABLE,
    LABEL,
    DATEPICKER,
    TOPO_JSON_MAP,
    /**
     * Point / marker map — plots one marker per row at its latitude/longitude, optionally colored by a
     * series column (e.g. app version). Distinct from TOPO_JSON_MAP (a region choropleth); this is the
     * saveable, query-backed cousin of the live sessions map.
     */
    GEO_POINT_MAP,
    /**
     * Live sessions map — a streaming point map fed by the `liveSessions` subscription (keyed by a
     * configured appId) rather than a query. Has no queryId; the client opens the subscription and
     * maintains a rolling, TTL-evicted set of active sessions.
     */
    LIVE_SESSIONS_MAP,
    /**
     * Stacked area chart — used by CumulativeFlowDiagram (Work Ops R32).
     * Contributed in Phase 18; existing renderers handle the new value
     * via the default-case branch until the UI lands.
     */
    STACKED_AREA,
    /**
     * Gantt timeline — used by RoadmapTimeline (Work Ops R32).
     * Contributed in Phase 18.
     */
    GANTT,
    /**
     * Node + edge graph — used by DependencyGraph (Work Ops R32).
     * Contributed in Phase 18.
     */
    GRAPH,
}


object AnalyticsVisualizationTypeMapper : EnumMapper<AnalyticsVisualizationType>({ AnalyticsVisualizationType.valueOf(it.uppercase()) })
