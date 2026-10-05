package bosca.analytics.server

import bosca.server.ServerCall
import bosca.server.HttpHeaders
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * Client analytics identity and typed per-call overrides. Headers and the authenticated
 * principal are read on access so reads before authentication do not cache an anonymous identity.
 *
 * Typical usage from a handler:
 * ```
 * call.analyticsContext = call.analyticsContext.copy(sessionId = sessionId)
 * ```
 *
 * Assignments replace the full context, including null identity fields. Use `copy` to retain other fields.
 * The value is stored in [ServerCall.attributes]. Context precedence
 * is documented on [AnalyticsErrorContextResolver].
 */
var ServerCall.analyticsContext: AnalyticsContext
    get() {
        (attributes[ANALYTICS_CONTEXT_ATTRIBUTE_KEY] as AnalyticsContext?)?.let { return it }
        val headers = request.headers
        return AnalyticsContext(
            appId = headers["X-App-ID"],
            appVersion = headers["X-App-Version"],
            installationId = headers["X-Installation-ID"],
            sessionId = attributes[AnalyticsMiddleware.ANALYTICS_SESSION_ID_ATTRIBUTE] as? String
                ?: headers[HttpHeaders.XSessionID],
            userId = authenticationContext.principal()?.id?.toString(),
        )
    }
    set(value) {
        attributes[ANALYTICS_CONTEXT_ATTRIBUTE_KEY] = value
    }

/** Attribute key for typed per-call analytics overrides. */
internal const val ANALYTICS_CONTEXT_ATTRIBUTE_KEY: String = "bosca.analytics.context"

/** Includes explicit null identity when a handler has replaced the call context. */
internal fun ServerCall.analyticsContextMap(): Map<String, Any?> =
    analyticsContext.toMap(includeNullIdentity = attributes.containsKey(ANALYTICS_CONTEXT_ATTRIBUTE_KEY))

/**
 * Runs [block] with this call's analytics identity, including `X-BA-Session-ID`.
 * A missing call adds no identity; enclosing coroutine context remains available.
 * Identity is read on access so authentication and per-call overrides are visible within the scope.
 */
suspend fun <T> ServerCall?.withAnalyticsContext(block: suspend () -> T): T {
    if (this == null) return block()
    val parent = currentCoroutineContext()[AnalyticsContextElement]
    return withContext(AnalyticsContextElement {
        val inherited = if (parent == null) emptyMap() else parent.entries.toMap()
        AnalyticsContext.fromEntries(inherited + analyticsContextMap())
    }) { block() }
}
