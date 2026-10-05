package bosca.telemetry

import bosca.di.provides
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule
import bosca.server.ServerCall
import bosca.server.middleware.CallLoggingMiddleware
import bosca.server.middleware.ResponseCounterMiddleware
import bosca.server.middleware.TracingMiddleware

private val HEALTH_CHECK_PATHS = setOf("/api/v1/live", "/api/v1/health", "/api/v1/ready")

internal fun isHealthCheck(path: String): Boolean = path in HEALTH_CHECK_PATHS

/**
 * Initializes OpenTelemetry instrumentation and installs the request-observing middleware — call
 * logging and the response-code [ResponseCounterMiddleware] — both filtering health-check endpoints.
 * The service identity comes from `server.name` (default `bosca`) and is shared by tracing and counters.
 */
class MonitoringModule : BoscaApplicationModule {
    override suspend fun install(application: BoscaApplication) = with(application) {
        val serviceName = environment.config.propertyOrNull("server.name")?.getString() ?: "bosca"
        val openTelemetry = getOpenTelemetry(serviceName = serviceName)

        provides(singleton = true) {
            openTelemetry
        }

        // Health probes are noise for both logs and rate metrics.
        val notAHealthCheck: (ServerCall) -> Boolean = { call ->
            !isHealthCheck(call.request.path)
        }

        install(TracingMiddleware(openTelemetry.getTracer(serviceName)))
        install(CallLoggingMiddleware(notAHealthCheck))
        install(ResponseCounterMiddleware(serviceName, filter = notAHealthCheck))
    }
}
