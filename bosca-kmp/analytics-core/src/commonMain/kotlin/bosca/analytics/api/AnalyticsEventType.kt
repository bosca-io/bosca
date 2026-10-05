package bosca.analytics.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Event kinds accepted by the Bosca analytics collector. */
@Serializable
enum class AnalyticsEventType(val wireName: String) {
    @SerialName("session")
    SESSION("session"),

    /**
     * A user action or input. Scroll-depth measurements use this event type because scrolling is an
     * interaction, but each depth milestone is view-quality telemetry rather than another discrete
     * engagement or conversion.
     */
    @SerialName("interaction")
    INTERACTION("interaction"),

    /**
     * A visibility signal, not engagement. Analytics may treat it as engagement only when the event's
     * element type is `page`; other impressions are exposure metrics.
     */
    @SerialName("impression")
    IMPRESSION("impression"),

    @SerialName("completion")
    COMPLETION("completion"),

    @SerialName("installation")
    INSTALLATION("installation"),

    @SerialName("error")
    ERROR("error"),

    @SerialName("heartbeat")
    HEARTBEAT("heartbeat"),
}
