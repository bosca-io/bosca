package bosca.experimentation.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Categorizes the type of change that occurred to an experiment.
 *
 * Subscribers (notably the `FeatureFlagServiceImpl` cache layer) use the
 * action only as a hint that *something* changed for the referenced flag —
 * the cache invalidation is uniform regardless of which action fired,
 * because the running-experiment lookup result depends on status, rule
 * attachment, and existence all at once.
 */
@Serializable
enum class ExperimentUpdateAction {
    /** A new experiment was created. */
    CREATED,
    /** Existing experiment fields were edited (rule attachment, dates, etc.). */
    UPDATED,
    /** The experiment's lifecycle status changed (e.g. DRAFT → RUNNING). */
    STATUS_CHANGED,
    /** The experiment was deleted. */
    DELETED,
}

/**
 * Event published via PubSub when an experiment is created, edited,
 * status-changed, or deleted, so any node caching
 * `getRunningByFlagAndRule` results can drop the affected entries.
 *
 * Carries [flagId] (and not just [experimentId]) so cache subscribers
 * can do prefix-based invalidation against `(flagId, ruleId)` keys
 * without first looking up the experiment to find its flag — the
 * lookup itself would defeat the cache.
 */
@Serializable
data class ExperimentUpdated(
    @Contextual
    val experimentId: UUID,
    @Contextual
    val flagId: UUID,
    val action: ExperimentUpdateAction,
)

/** PubSub channel name for experiment update events. */
const val EXPERIMENT_UPDATED_CHANNEL = "experimentation.experiments.updated"
