package bosca.analytics.model

import bosca.serialization.OffsetDateTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The result of executing an analytics query.
 *
 * @param records the result rows, each encoded as a JSON object
 * @param cached whether the records were served from the query result cache
 *   instead of being computed by the analytics store for this request
 * @param refreshedAt when the records were last computed by the analytics store;
 *   `null` for uncached executions, where the records are always current
 * @property stale whether the cached records are older than the query's configured
 *   refresh interval; always `false` for uncached executions
 */
@Serializable
class AnalyticsQueryResponse(
    val records: List<@Contextual JsonElement>,
    val cached: Boolean = false,
    val refreshedAt: OffsetDateTime? = null,
) {
    /**
     * Whether this response is the last known good cached result awaiting refresh.
     *
     * This is a body property, rather than a new primary-constructor parameter, so
     * the existing published three-argument JVM constructor remains available.
     */
    var stale: Boolean = false
        private set

    /**
     * Creates a response with explicit cache freshness metadata while preserving
     * the original three-argument constructor for existing callers.
     */
    constructor(
        records: List<JsonElement>,
        cached: Boolean,
        refreshedAt: OffsetDateTime?,
        stale: Boolean,
    ) : this(records, cached, refreshedAt) {
        this.stale = stale
    }
}
