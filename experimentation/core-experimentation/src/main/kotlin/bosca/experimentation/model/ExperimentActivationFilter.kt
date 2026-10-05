package bosca.experimentation.model

import bosca.analytics.model.EventType
import kotlinx.serialization.Serializable

/**
 * Selects the analytics event that makes an assigned subject eligible for experiment analysis.
 *
 * The first matching event after assignment becomes the subject's activation time. Conversion
 * goals and event-count outcomes only inspect later events, while session-duration goals use the
 * activation event as the beginning of the observed session. A null filter on [Experiment]
 * preserves assignment-time eligibility for experiments that do not need an activation gate.
 */
@Serializable
data class ExperimentActivationFilter(
    val eventType: EventType? = null,
    val elementType: String? = null,
    val elementId: String? = null,
    /** Exact `page.path` match. Combined with [pagePathPrefixes] as alternatives. */
    val pagePath: String? = null,
    /** Alternative `page.path` prefixes, such as `/articles/`, `/talks/`, and `/studies/`. */
    val pagePathPrefixes: List<String> = emptyList(),
    val itemExtraKey: String? = null,
    /** Exact, case-sensitive scalar value match. Null means key presence is sufficient. */
    val itemExtraValue: String? = null,
)
