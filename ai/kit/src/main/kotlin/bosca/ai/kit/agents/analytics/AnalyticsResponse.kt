package bosca.ai.kit.agents.analytics

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable

/**
 * The SQL sub-agent's **structured** response — not text. It carries the human-facing [summary], the
 * [query] that produced it, the tabular data ([columns]/[rows]), and a suggested [visualization], so
 * a caller can render a chart/table rather than re-parse a sentence. Produced via structured output
 * (`structuredOutputWithToolsStrategy`) after the agent's tool loop runs the query.
 */
@Serializable
@LLMDescription("The answer to an analytics question, with its data and a suggested visualization.")
data class AnalyticsResponse(
    @property:LLMDescription("One concise sentence answering the question, stating the key figure(s).")
    val summary: String,
    @property:LLMDescription("The SQL query that produced the answer.")
    val query: String = "",
    @property:LLMDescription("Column names for the result table.")
    val columns: List<String> = emptyList(),
    @property:LLMDescription("Result rows, each a list of cell values aligned to columns.")
    val rows: List<List<String>> = emptyList(),
    @property:LLMDescription("Suggested visualization using an AnalyticsVisualizationType name such as NUMBER, BAR, LINE, PIE, DOUGHNUT, SCATTER, TABLE, or LABEL.")
    val visualization: String = "TABLE",
    @property:LLMDescription("The saved query id created or updated during this request, when applicable.")
    val savedQueryId: String? = null,
    @property:LLMDescription("The saved query key created or updated during this request, when applicable.")
    val savedQueryKey: String? = null,
    @property:LLMDescription("The visualization id created or updated during this request, when applicable.")
    val visualizationId: String? = null,
    @property:LLMDescription("The dashboard id created or updated during this request, when applicable.")
    val dashboardId: String? = null,
    @property:LLMDescription("Brief purpose and conclusion annotations for recorded tool steps. Use the recorded sequence when known; SQL may also identify query steps.")
    val annotations: List<InvestigationAnnotation> = emptyList(),
)

/** A model-authored explanation that may be joined only to a matching recorded step. */
@Serializable
data class InvestigationAnnotation(
    val sequence: Int? = null,
    val sql: String? = null,
    val purpose: String = "",
    val conclusion: String = "",
)
