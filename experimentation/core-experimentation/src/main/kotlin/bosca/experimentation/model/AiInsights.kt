package bosca.experimentation.model

import kotlinx.serialization.Serializable

/**
 * Structured LLM commentary attached to an [AnalysisReport] on top of the
 * deterministic verdict. Strictly additive — the deterministic `summary`
 * and `recommendation` remain the authoritative product surface, and this
 * payload is persisted to a separate `ai_insights` jsonb column only
 * after the analyzer's verdict-acknowledgement check has passed.
 *
 * The fields here are the complete user-facing schema. The LLM also
 * returns a `verdictAcknowledged` value as a guardrail, but that is a
 * verification artifact and is stripped before this type is constructed
 * so it never leaks into the database or the API.
 *
 * @property hypothesisAssessment 1–3 sentences reconciling the observed
 *   result against the operator's hypothesis.
 * @property crossGoalPatterns 1–3 sentences describing observations that
 *   span more than one conversion goal (e.g. "wins on signups but
 *   regresses on revenue"). When there is only one goal, the analyzer is
 *   instructed to say so briefly.
 * @property followUpExperiments One or two concrete next experiments to
 *   consider, grounded in the observed data.
 * @property srmRootCauseHints Common bucketing/tracking failure modes to
 *   investigate first. Only populated when the deterministic verdict is
 *   `SRM_FAILED`; null otherwise.
 */
@Serializable
data class AiInsights(
    val hypothesisAssessment: String,
    val crossGoalPatterns: String,
    val followUpExperiments: List<String>,
    val srmRootCauseHints: String? = null,
)
