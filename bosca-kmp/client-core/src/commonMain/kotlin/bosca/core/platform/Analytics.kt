package bosca.core.platform

/**
 * Abstraction over platform analytics SDKs (e.g., Firebase Analytics).
 *
 * Implementations translate calls into the appropriate vendor-specific API.
 * A no-op stub ([AnalyticsStub]) is provided for environments where analytics
 * is unavailable or not desired.
 */
interface Analytics {

    /**
     * Records a named analytics event with optional key-value parameters.
     *
     * @param name the event name, typically a short snake_case identifier
     * @param params additional metadata to attach to the event
     */
    fun logEvent(name: String, params: Map<String, Any> = emptyMap())

    /**
     * Records an error event so that application failures can be tracked,
     * aggregated, and alerted on through the analytics pipeline.
     *
     * @param message a human-readable description of what went wrong
     * @param type the error class or category (e.g. "NullPointerException")
     * @param stackTrace the full stack trace string, if available
     * @param fatal whether the error caused the application to crash
     * @param code an optional application-specific error code
     * @param params additional metadata to attach to the error event
     */
    fun logError(
        message: String,
        type: String? = null,
        stackTrace: String? = null,
        fatal: Boolean = false,
        code: String? = null,
        params: Map<String, Any> = emptyMap()
    )
}

object AnalyticsStub : Analytics {

    override fun logEvent(name: String, params: Map<String, Any>) {
        Log.d("Analytics: $name $params")
    }

    override fun logError(
        message: String,
        type: String?,
        stackTrace: String?,
        fatal: Boolean,
        code: String?,
        params: Map<String, Any>
    ) {
        Log.d("Analytics Error: message=$message type=$type fatal=$fatal code=$code $params")
    }
}
