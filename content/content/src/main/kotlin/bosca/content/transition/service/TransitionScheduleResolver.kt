package bosca.content.transition.service

import bosca.content.collection.model.ContentItem
import bosca.content.transition.model.BeginTransitionInput
import bosca.serialization.OffsetDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaDuration

/**
 * Immutable result of computing the scheduling requirements for a transition.
 *
 * @property effectiveStateId the workflow state to actually transition to (may differ from
 *           the originally requested state when an advertised window redirects a published request)
 * @property delay the future timestamp at which the transition jobs should fire, or null for immediate execution
 * @property request the (possibly modified) transition request reflecting the effective state and delay
 */
data class TransitionSchedule(
    val effectiveStateId: String,
    val delay: OffsetDateTime?,
    val request: BeginTransitionInput,
)

/**
 * Computes scheduling requirements for workflow transitions based on content attributes
 * and requested state.
 *
 * Handles two scheduling scenarios:
 * - **Explicit scheduling** via [BeginTransitionInput.stateValid] — the caller specifies when the transition should take effect
 * - **Epoch-based scheduling** for advertised/published workflows — content attributes contain future timestamps
 *   that gate when the content should become advertised or published
 *
 * For advertised/published transitions, the resolver also handles state redirection:
 * when content has an advertised epoch that precedes its published epoch, a request to
 * transition directly to "published" is redirected to "advertised" first, ensuring
 * the content flows through the full lifecycle.
 */
object TransitionScheduleResolver {

    /**
     * Computes the effective schedule for a transition request.
     *
     * @param request the original transition request
     * @param content the content item being transitioned, used to read epoch attributes
     * @return a [TransitionSchedule] with the effective state, delay, and adjusted request
     */
    fun resolve(request: BeginTransitionInput, content: ContentItem): TransitionSchedule {
        var effectiveStateId = request.stateId
        var delay: OffsetDateTime? = null

        request.stateValid?.let { stateValid ->
            if (stateValid > OffsetDateTime.now()) {
                delay = stateValid
            }
        }

        if (delay == null && (effectiveStateId == "advertised" || effectiveStateId == "published")) {
            val advertisedEpoch = content.attributes.epochAttribute("advertised")
            val publishedEpoch = content.attributes.epochAttribute("published")

            if (content.workflowStateId != "advertised") {
                val effectiveAdvertisedEpoch = getEffectiveAdvertisedEpoch(advertisedEpoch, publishedEpoch)
                if (effectiveAdvertisedEpoch != null) {
                    if (effectiveStateId == "published") {
                        effectiveStateId = "advertised"
                    }
                    delay = effectiveAdvertisedEpoch.toFutureDelay()
                }
            }

            if (delay == null && effectiveStateId == "published" && publishedEpoch != null && publishedEpoch > 0) {
                delay = publishedEpoch.toFutureDelay()
            }
        }

        val adjustedRequest = if (effectiveStateId != request.stateId || delay != null) {
            request.copy(
                stateId = effectiveStateId,
                stateValid = if (delay != null && delay > OffsetDateTime.now()) delay else request.stateValid
            )
        } else {
            request
        }

        return TransitionSchedule(
            effectiveStateId = effectiveStateId,
            delay = delay,
            request = adjustedRequest,
        )
    }

    /**
     * Converts an epoch-millisecond timestamp into a future [OffsetDateTime] if the timestamp
     * is still in the future. Returns null for past timestamps (no delay needed).
     */
    @OptIn(ExperimentalTime::class)
    fun Long.toFutureDelay(): OffsetDateTime? {
        val now = System.currentTimeMillis()
        if (this > now) {
            val diff = this - now
            return OffsetDateTime.now().plus(diff.milliseconds.toJavaDuration())
        }
        return null
    }

    /**
     * Converts an epoch-millisecond timestamp into an [OffsetDateTime] offset from now,
     * even if the timestamp is in the past. Used when a delay target is needed regardless
     * of whether it has already passed (e.g., for the advertised→published chaining in executors).
     */
    @OptIn(ExperimentalTime::class)
    fun Long.toDelay(): OffsetDateTime {
        val diff = this - System.currentTimeMillis()
        return OffsetDateTime.now().plus(diff.milliseconds.toJavaDuration())
    }

    /**
     * Determines whether the advertised epoch should be used for scheduling.
     * The advertised epoch is only valid if it is set (> 0) and precedes the published epoch.
     */
    fun getEffectiveAdvertisedEpoch(advertisedEpoch: Long?, publishedEpoch: Long?): Long? {
        if (advertisedEpoch == null || advertisedEpoch <= 0) return null
        if (publishedEpoch != null && advertisedEpoch >= publishedEpoch) return null
        return advertisedEpoch
    }

    /**
     * Reads an epoch-millisecond attribute from a content item's JSON attributes.
     * Tolerates both numeric and string-encoded values for robustness against
     * partially-typed client input.
     */
    fun JsonElement?.epochAttribute(key: String): Long? =
        ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.let {
            it.longOrNull ?: it.content.toLongOrNull()
        }
}
