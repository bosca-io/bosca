package bosca.experimentation.jobs

import bosca.experimentation.model.AiInsights
import bosca.service.Service

/**
 * Optional, **strictly additive** AI insights writer for experiment analysis.
 *
 * Implementations receive the structured [DeterministicAnalysis] and return
 * an [AiInsights] payload that the executor persists into a separate
 * `ai_insights` jsonb column on the analysis report. The deterministic
 * `summary` and `recommendation` are the authoritative product surface and
 * are *never* replaced — implementations must not be wired into that path.
 *
 * The contract for an implementation is:
 *
 *  1. Add value the deterministic pipeline cannot: hypothesis ↔ result
 *     reconciliation, cross-goal pattern recognition, follow-up
 *     experiment suggestions, SRM root-cause hints. Paraphrasing the
 *     deterministic prose is not a useful job for a model and should
 *     be left to the deterministic templater.
 *
 *  2. Never contradict the deterministic verdict. Implementations are
 *     expected to perform their own verdict-acknowledgement check before
 *     returning, dropping any LLM response that would attach an
 *     "AI says don't ship" block to a deterministic SHIP banner.
 *
 *  3. Treat any failure as recoverable. Returning null, throwing, or
 *     producing a malformed payload all lead the executor to persist
 *     the report with `ai_insights = null`, leaving the deterministic
 *     prose untouched. The AI is a bonus, never a blocker.
 *
 * Implementations are wired through DI and resolved by
 * [ExperimentAnalysisJobExecutor] via `provide<ExperimentAiAnalyzer>()`.
 * When no implementation is registered (e.g. no AI credentials are
 * configured) the executor simply skips the AI step.
 */
interface ExperimentAiAnalyzer : Service {

    /**
     * Generates structured AI insights for the given deterministic
     * analysis, or null when the model could not produce a valid
     * response. The structured numbers in [analysis] are the ground
     * truth; implementations should reference them by value rather
     * than computing their own.
     *
     * Implementations must enforce the verdict-acknowledgement guardrail
     * internally and return null on any check failure rather than
     * surfacing a payload that could contradict the deterministic
     * verdict.
     */
    suspend fun summarize(analysis: DeterministicAnalysis): AiInsights?
}
