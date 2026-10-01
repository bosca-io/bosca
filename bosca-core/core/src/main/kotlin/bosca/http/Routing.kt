package bosca.http

import bosca.cache.RequestCache
import bosca.cache.asCoroutineContext
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.di.provide
import bosca.security.service.AuthenticationContext
import bosca.server.routing.RoutingContext
import kotlinx.coroutines.withContext

/**
 * Executes [block] within a database connection and request cache context,
 * ensuring both are properly acquired and released around the handler body.
 */
suspend fun <T> RoutingContext.withRequestContext(block: suspend RoutingContext.() -> T): T {
    val connectionPool = provide<ConnectionPool>()
    val cache = RequestCache(provide(), provide())
    val connection = connectionPool.connection()
    try {
        return withContext(connection.asCoroutineContext() + cache.asCoroutineContext()) {
            block()
        }
    } finally {
        connection.release()
    }
}

/**
 * Creates an [AuthenticationContext] from the current call's authentication state
 * and the registered authentication providers.
 */
suspend fun RoutingContext.authenticationContext() = AuthenticationContext(call.authenticationContext, provide())
