package bosca.events.catalog

import kotlinx.serialization.Serializable

/**
 * A single filterable field on an event payload, derived from the event's
 * kotlinx-serialization [descriptor][kotlinx.serialization.descriptors.SerialDescriptor].
 *
 * @property name the field name as it appears in the serialized event payload
 * @property type a coarse, UI-friendly type label (e.g. `String`, `Long`, `Boolean`,
 *   or the serial name for complex/contextual types); a trailing `?` marks a nullable field
 */
@Serializable
data class EventField(
    val name: String,
    val type: String,
)

/**
 * Catalog entry describing one `@JobEvent`, so trigger/automation UIs can offer event
 * pickers and field-aware filter builders instead of free-text event names and raw JSON.
 *
 * @property fqdn the fully-qualified event identifier (`package.SimpleName`); this is the
 *   exact key passed to `PipelineEventDispatcher.dispatch` and stored as a triggered pipeline's
 *   accepted input type / on WorkOps automation rules
 * @property displayName a human-friendly name; falls back to the event's simple class name
 * @property description a short explanation of when the event fires
 * @property pubsubChannel the pub/sub channel the event publishes to, or null if it does not publish
 * @property jobNames simple names of the jobs enqueued when the event is dispatched
 * @property fields the filterable fields on the event payload
 */
@Serializable
data class EventDescriptor(
    val fqdn: String,
    val displayName: String,
    val description: String,
    val pubsubChannel: String?,
    val jobNames: List<String>,
    val fields: List<EventField>,
)
