package bosca.recommendations.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Result of provisioning the recommendation-engine A/B test scaffold — the `recommendation-engine`
 * feature flag (with `ml`/`heuristic` variations split 50/50), the experiment attached to that split,
 * and its conversion goals.
 */
@Serializable
data class EngineExperimentProvisioning(
    /** The experiment id — open it in the experiments UI to review and start the test. */
    @Contextual
    val experimentId: UUID,
    /** The feature flag key driving the ML-vs-heuristic split. */
    val flagKey: String,
    /** True if the scaffold was newly created; false if it already existed (provisioning is idempotent). */
    val created: Boolean,
)
