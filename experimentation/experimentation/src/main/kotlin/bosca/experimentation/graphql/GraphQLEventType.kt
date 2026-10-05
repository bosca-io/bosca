package bosca.experimentation.graphql

import bosca.analytics.model.EventType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GraphQL-facing enum for analytics event types.
 *
 * Mirrors the wire-format [EventType] but lives in its own namespace and
 * uses PascalCase serial names so that:
 *
 *  1. graphql-java's enum coercion (which uses `Enum.name()`) finds the
 *     constant by name when serializing responses.
 *  2. The KSP-generated GraphQL input dispatcher's
 *     `decodeFromJsonElement(serializer())` call decodes the PascalCase
 *     string graphql-java forwards from the schema.
 *  3. The wire-format [EventType] enum can stay strictly lowercase
 *     (matching the SDK convention) without leaking PascalCase tolerance
 *     into the analytics-collector ingestion path or any other JSON
 *     decoder that touches it.
 *
 * The translation between this type and [EventType] happens at the GraphQL
 * service boundary — see [toEventType] / [fromEventType] below — so neither
 * side needs to know about the other's existence beyond a single mapping
 * function call.
 */
@Serializable
enum class GraphQLEventType {
    @SerialName("Session")
    Session,

    @SerialName("Interaction")
    Interaction,

    @SerialName("Impression")
    Impression,

    @SerialName("Completion")
    Completion,

    @SerialName("Installation")
    Installation,

    @SerialName("Assignment")
    Assignment,

    @SerialName("Error")
    Error;

    /**
     * Convert this GraphQL-facing enum value to the wire-format [EventType]
     * used by the analytics SDK and the warehouse storage layer. The two
     * enums have the same variants in the same order, so the mapping is
     * 1:1 by name.
     */
    fun toEventType(): EventType = EventType.valueOf(name)

    companion object {
        /**
         * Convert a wire-format [EventType] (returned by the conversion-goal
         * model) into the GraphQL-facing enum used by the schema.
         */
        fun fromEventType(eventType: EventType): GraphQLEventType =
            valueOf(eventType.name)
    }
}
