package bosca.server.middleware

import bosca.server.ServerCall
import org.slf4j.LoggerFactory

/**
 * CallMiddleware that logs each HTTP request with its method, URI, and response status.
 * Supports filtering to exclude certain paths (e.g., health check endpoints).
 */
class CallLoggingMiddleware(
    private val filter: (ServerCall) -> Boolean = { true }
) : CallMiddleware {
    private val log = LoggerFactory.getLogger("bosca.server.CallLogging")

    override suspend fun afterCall(call: ServerCall) {
        if (!filter(call)) return
        val method = call.request.httpMethod.value
        val path = call.request.path
        val status = call.response.status()?.value ?: 0
        log.info("{} {} -> {}", method, path, status)
    }
}
