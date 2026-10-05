package bosca.analytics.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Captures details about an error that occurred on the client, allowing
 * the analytics pipeline to record, aggregate, and alert on application
 * failures across devices and sessions.
 */
@Serializable
data class ErrorInfo(
    val message: String,
    val type: String? = null,
    @SerialName("stack_trace")
    val stackTrace: String? = null,
    val fatal: Boolean = false,
    val code: String? = null,
    /**
     * Stable per-(app, error class, top stack frames) identifier assigned by the
     * server-side error fingerprint transform. Always null on client submissions;
     * populated by [bosca.analytics.transform.ErrorFingerprintTransform] before
     * the event reaches the event repository.
     */
    val fingerprint: String? = null,
    /**
     * Opaque JSON-encoded context map attached by the server-side
     * analytics client when an error is captured. Produced by
     * `bosca.analytics.server.AnalyticsMiddleware` and the
     * `ServerAnalyticsClient.captureException` call sites, this field
     * carries request metadata (method, path, route, status, principal
     * id, session id, allowlisted headers) and any per-call
     * [`call.analyticsContext`] typed request context or
     * `withAnalyticsContext { ... }` ambient entries merged by
     * `AnalyticsErrorContextResolver`.
     *
     * The value is always a JSON object (or null). Consumers that want
     * structured access should `json.parseToJsonElement(contextJson)`.
     * Analytics clients running on real browsers / mobile devices never
     * set this field.
     */
    @SerialName("context_json")
    val contextJson: String? = null,
)
