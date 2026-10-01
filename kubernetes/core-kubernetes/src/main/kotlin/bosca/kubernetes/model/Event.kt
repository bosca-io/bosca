package bosca.kubernetes.model

import bosca.serialization.OffsetDateTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Coarse severity bucket — matches the GraphQL `EventLevel` enum. */
@Serializable
enum class EventLevel { INFO, WARN, ERROR }

/**
 * A kubernetes event (`v1.Event` / `events.k8s.io/v1.Event`) as
 * surfaced by `kubernetes-controller`. Shape mirrors the GraphQL
 * `Event` type. The `when` field is the controller-formatted relative
 * timestamp (e.g. `2m ago`) so all clients render the same string;
 * `timestamp` carries the absolute ISO-8601 value for sorting and
 * deep-link state.
 */
@Serializable
data class K8sEvent(
    val id: String,
    val level: EventLevel,
    val `when`: String,
    @Contextual
    val timestamp: OffsetDateTime,
    val namespace: String,
    val involvedObject: String,
    val message: String,
    val reason: String,
)

/** Wire envelope for `GET /clusters/{id}/events`. */
@Serializable
data class EventsResponse(val items: List<K8sEvent>)
