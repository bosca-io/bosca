package bosca.server.routing

/**
 * Describes a single registered route in the router tree for introspection purposes.
 * Produced by [Router.collectRouteDescriptions] and consumed by the JMX MBean to
 * expose route information to jconsole.
 */
data class RouteDescription(
    /** The type of route (HTTP, WebSocket, SSE, or static content). */
    val type: RouteType,
    /** The full path pattern including parent prefixes (e.g., "/api/v1/users/{id}"). */
    val path: String,
    /** The HTTP method for HTTP routes, null for other types. */
    val method: String? = null,
    /** Whether this route requires authentication. */
    val authenticated: Boolean = false,
    /** For static routes, indicates the source type ("classpath" or "filesystem"). */
    val staticSourceType: String? = null,
)

/**
 * Categorizes route types for JMX introspection display.
 */
enum class RouteType {
    HTTP, WEBSOCKET, SSE, STATIC
}
