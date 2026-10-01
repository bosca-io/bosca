package bosca.ai.chat.model

import kotlinx.serialization.Serializable

/** The kind of recorded work represented by an [AnalyticsInvestigationStep]. */
@Serializable
enum class AnalyticsInvestigationKind {
    DISCOVERY,
    QUERY,
    SAVED_QUERY,
    ARTIFACT,
}

/**
 * One ground-truth step in an analytics investigation.
 *
 * Execution facts are recorded by the tool path. [purpose] and [conclusion] are optional model
 * annotations joined to that record after the run; they never create or replace recorded steps.
 */
@Serializable
data class AnalyticsInvestigationStep(
    val sequence: Int,
    val kind: AnalyticsInvestigationKind,
    val tool: String,
    val sql: String? = null,
    val resultSummary: String,
    val startedAt: String,
    val purpose: String? = null,
    val conclusion: String? = null,
)
