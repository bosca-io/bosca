package bosca.experimentation.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * An automated analysis report for an experiment.
 *
 * [summary] and [recommendation] are the **deterministic** prose written
 * by `ExperimentAnalysis.renderVerdict` and are the authoritative product
 * surface — chi-squared / Welch's t / Bonferroni decisions are described
 * here in plain language driven entirely by the structured pipeline.
 * [details] is the structured per-goal/per-variation JSON the admin UI
 * uses to render tables and badges.
 *
 * [aiInsights] is the persisted jsonb representation of the optional
 * [AiInsights] commentary written by the LLM analyzer on top of the
 * deterministic result. It is stored as [JsonElement] at this layer so
 * the repository's jsonb binding stays generic; the GraphQL controller
 * decodes it into the typed [AiInsights] shape at the API boundary. It
 * is strictly additive: the analysis job never replaces [summary] /
 * [recommendation] with AI output, and the analyzer rejects any LLM
 * response that fails its internal verdict-acknowledgement guardrail,
 * so the AI section can never silently contradict the deterministic
 * decision. Null when no AI analyzer is wired or when the verifier
 * rejected the response.
 */
@BatchKey("id")
@Serializable
data class AnalysisReport(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("experiment_id")
    @Contextual
    val experimentId: UUID,
    val summary: String,
    val recommendation: String,
    @Contextual
    val details: JsonElement,
    val confidence: Double? = null,
    @ColumnName("ai_insights")
    @Contextual
    val aiInsights: JsonElement? = null,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now()
)
