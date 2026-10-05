package bosca.server.routing

import bosca.server.BoscaApplication
import bosca.server.ServerCall

/**
 * The context available inside a route handler, providing access to the current [call]
 * and the parent [application].
 *
 * Route handlers receive this as their receiver, allowing direct access to request/response
 * operations via `call` and application-level services via `application`.
 */
class RoutingContext(
    val call: ServerCall,
    val application: BoscaApplication
) {
    /** Shortcut to the application logger. */
    val log get() = application.log
}
