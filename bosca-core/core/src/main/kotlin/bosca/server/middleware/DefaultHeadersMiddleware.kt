package bosca.server.middleware

import bosca.server.ServerCall

/**
 * CallMiddleware that adds configured default headers and security headers to every HTTP response.
 *
 * Prevents browsers from MIME-sniffing responses away from the declared content type.
 */
class DefaultHeadersMiddleware(
    private val headers: Map<String, String>,
    private val enableHsts: Boolean = false,
    private val hstsMaxAge: Long = 31536000L,
) : CallMiddleware {
    override suspend fun beforeCall(call: ServerCall) {
        headers.forEach { (name, value) ->
            call.response.header(name, value)
        }
        if (!call.application.developmentMode) {
            call.response.header("X-Content-Type-Options", "nosniff")
            if (enableHsts) {
                call.response.header("Strict-Transport-Security", "max-age=$hstsMaxAge; includeSubDomains")
            }
        }
    }
}
