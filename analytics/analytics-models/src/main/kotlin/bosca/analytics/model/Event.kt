package bosca.analytics.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Event(
    val created: Long,
    @SerialName("created_micros")
    val createdMicros: Long? = 0,
    val type: EventType,
    val element: Element? = null,
    /**
     * Snapshot of the page the user was on when this event was emitted.
     * Optional for backward compatibility with clients predating Phase 3 of
     * the conversion-goals overhaul, and for non-browser SDKs that have no
     * `window` to read from. Server-side queries (notably the experimentation
     * aggregation job's `pagePath` filter) read [Page.path] from this field.
     */
    val page: Page? = null,
    @SerialName("client_id")
    val clientId: String? = null,
    val error: ErrorInfo? = null
)