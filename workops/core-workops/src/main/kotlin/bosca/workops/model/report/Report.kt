package bosca.workops.model.report

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * R32 — every Work Ops report kind. Each name maps to a seeded
 * `AnalyticsVisualization` row keyed `workops.<report_kind>`
 * (lower-snake) so dashboards can reference the visualization
 * by stable id.
 */
@Serializable
enum class ReportKind {
    BURN_DOWN,
    BURN_UP,
    CUMULATIVE_FLOW,
    VELOCITY,
    THROUGHPUT,
    CYCLE_TIME,
    LEAD_TIME,
    CONTROL_CHART,
    CREATED_VS_RESOLVED,
    WORKLOAD_HEATMAP,
    SLA_COMPLIANCE,
    SPRINT_REPORT,
    OKR_PROGRESS,
    ROADMAP_TIMELINE,
    DEPENDENCY_GRAPH,
    ISSUE_TYPE_DISTRIBUTION,
    PRIORITY_DISTRIBUTION,
    AGING_WORK_IN_PROGRESS,
    EPIC_PROGRESS,
    PORTFOLIO_ROLLUP,
    CUMULATIVE_STORY_POINT_BURN_UP,
}

/**
 * Per-kind input payload. Each computer asks for a narrow set of
 * arguments that constrain the data window.
 */
@Serializable
sealed class ReportInput {
    @Serializable @SerialName("ScopeOnly")
    data class ScopeOnly(@Contextual val projectId: UUID? = null) : ReportInput()

    @Serializable @SerialName("Sprint")
    data class Sprint(@Contextual val sprintId: UUID) : ReportInput()

    @Serializable @SerialName("DateRange")
    data class DateRange(
        @Contextual val from: OffsetDateTime,
        @Contextual val to: OffsetDateTime,
        @Contextual val projectId: UUID? = null,
    ) : ReportInput()

    @Serializable @SerialName("Program")
    data class Program(@Contextual val programId: UUID) : ReportInput()
}

/**
 * Typed series payload. `series` is `Map<seriesName, List<Point>>`
 * — the visualization renderer reshapes from there.
 */
@Serializable
data class ReportResult(
    val kind: ReportKind,
    val series: Map<String, List<ReportPoint>>,
    val labels: List<String> = emptyList(),
)

@Serializable
data class ReportPoint(
    /** X axis (numeric or date — the renderer decides). */
    val x: String,
    val y: Double,
)
