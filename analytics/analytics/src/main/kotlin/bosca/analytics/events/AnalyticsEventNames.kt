package bosca.analytics.events

/**
 * The synthetic platform event names the analytics processor dispatches for the whole event batch,
 * and the single source of truth shared by the transform chain (which dispatches them) and the
 * [AnalyticsEventCatalogRegistrar] (which catalogs them so triggered pipelines can bind to and
 * deserialize them).
 *
 * There is no per-event-type routing: a bound pipeline receives the whole [AnalyticsScriptEvent]
 * batch and filters it itself. Two families exist, distinguished only by delivery:
 * - [TRANSFORM] — the inline path: a triggered pipeline runs synchronously and may transform the
 *   batch before it is stored.
 * - [NOTIFY] — the fire-and-forget path: a triggered pipeline reacts asynchronously without blocking
 *   or modifying ingestion.
 */
object AnalyticsEventNames {

    /** Inline transform event: a triggered pipeline may transform the whole batch before storage. */
    const val TRANSFORM = "analytics.transform"

    /** Fire-and-forget notify event: a triggered pipeline reacts to the whole batch asynchronously. */
    const val NOTIFY = "analytics.notify"

    /** Every analytics event name (transform + notify). */
    val all: List<String> = listOf(TRANSFORM, NOTIFY)
}
