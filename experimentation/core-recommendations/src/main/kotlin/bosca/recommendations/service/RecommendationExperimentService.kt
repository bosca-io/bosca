package bosca.recommendations.service

import bosca.recommendations.model.EngineExperimentProvisioning
import bosca.service.Service

/**
 * Provisions the recommendation system's built-in A/B test that measures the ML ranker's engagement
 * lift over the heuristic assembler.
 */
interface RecommendationExperimentService : Service {

    /**
     * Provisions — idempotently — the ML-vs-heuristic recommendation A/B test: the `recommendation-engine`
     * feature flag with `ml`/`heuristic` variations split 50/50, an experiment attached to that split, and
     * two conversion goals (engagement, and positive-feedback completions). The scaffold is created
     * dormant — the flag is DISABLED and the experiment DRAFT — so it changes nothing for real users until
     * it is explicitly started from the experiments UI. If the scaffold already exists, the existing
     * experiment is returned unchanged.
     */
    suspend fun provisionEngineExperiment(): EngineExperimentProvisioning

    /**
     * Provisions the model-vs-model online A/B test: the `recommendation-model` feature flag whose two
     * variations carry the [championVersion] and [challengerVersion] TF Serving model versions, split
     * 50/50, plus an experiment and the same engagement + positive-feedback goals. The recommendation
     * serve path routes each arm's traffic to its variation's model version, so the experiment measures
     * which version wins on real engagement. This is the product-quality comparison; export-time checks
     * only establish that each artifact can serve. The flag is upserted to the requested version pair; the
     * experiment is created dormant (or reused if present).
     */
    suspend fun provisionModelExperiment(
        championVersion: Long,
        challengerVersion: Long,
    ): EngineExperimentProvisioning
}
