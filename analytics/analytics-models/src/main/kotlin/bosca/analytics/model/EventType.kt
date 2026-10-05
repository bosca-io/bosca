package bosca.analytics.model

import bosca.db.annotation.DbMapper
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Categorizes analytics events by the kind of user behavior they represent.
 * Each variant maps to a `@SerialName` matching the lowercase string value
 * sent by the client-side SDK, so kotlinx.serialization can decode incoming
 * JSON payloads from the analytics-collector wire format.
 *
 * The DB column on `conversion_goals.event_type` and the Iceberg `type`
 * column on the events table both store the PascalCase `.name` form via
 * [EventTypeMapper] and `IcebergEventsToRecordTransform`, so they join
 * byte-identically against each other when the experimentation aggregation
 * job runs its Trino query (locked in by
 * `ConversionGoalEventTypeSerializationTest`).
 *
 * **GraphQL does not use this enum directly.** The GraphQL schema exposes
 * a parallel `bosca.experimentation.graphql.GraphQLEventType` enum that
 * mirrors these variants in PascalCase form, and the experimentation
 * service maps between the two at the GraphQL boundary. Keeping the two
 * enums separate means this serializer can stay strict (one canonical
 * lowercase wire form) without leaking PascalCase tolerance into every
 * other JSON-decoding code path.
 */
@DbMapper(EventTypeMapper::class)
@Serializable
enum class EventType {
    @SerialName("session")
    Session,

    /**
     * A user action or input. Scroll-depth measurements remain interactions, but their milestones
     * describe view quality and must not be counted as additional discrete engagements or conversions.
     */
    @SerialName("interaction")
    Interaction,

    /**
     * Records visibility, not engagement. Analytics that infer activity or preference must exclude
     * impressions unless the event's `element.type` is `page`; page impressions represent navigation
     * to and interaction with that page. Queries explicitly measuring exposure may count other impressions.
     */
    @SerialName("impression")
    Impression,

    @SerialName("completion")
    Completion,

    @SerialName("installation")
    Installation,

    @SerialName("error")
    Error,

    /**
     * An ephemeral session keep-alive (~one per session per 15-min window). Not stored: the
     * `SessionHeartbeatTransform` increments the active-session counter and drops it before the event
     * store, so it never reaches Iceberg or the DB.
     */
    @SerialName("heartbeat")
    Heartbeat,

    /**
     * A durable assignment of a subject to a product-controlled value,
     * such as a feature-flag variation. Unlike an impression, this is
     * emitted only when the assignment is created or changes.
     *
     * Keep new values after the existing entries. Analytics spool files
     * use kotlinx ProtoBuf, whose default enum representation follows the
     * declaration order; inserting a value would renumber persisted events.
     */
    @SerialName("assignment")
    Assignment,
}
