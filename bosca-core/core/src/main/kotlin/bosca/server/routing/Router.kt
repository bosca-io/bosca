package bosca.server.routing

import bosca.server.ContentType
import bosca.server.HttpHeaders
import bosca.server.HttpMethod
import bosca.server.HttpStatusCode
import bosca.server.sse.ServerSSESession
import bosca.server.websocket.WebSocketSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File

/**
 * An HTTP router that supports path matching with parameters, nested routes,
 * authentication scopes, WebSocket endpoints, Server-Sent Events, and static content serving.
 *
 * Routes are registered using a DSL-style builder pattern and matched against incoming requests
 * via linear scan with support for path parameters (e.g., `{id}`). Child routers provide
 * hierarchical path prefix matching.
 *
 * **Important:** Routes use first-match-wins semantics. When registering routes with
 * overlapping patterns, register literal routes before parameterized routes to avoid
 * shadowing. For example, register `GET /users/me` before `GET /users/{id}`.
 */
class Router(private val prefix: String = "") {

    /** Pre-split prefix segments, computed once at construction to avoid per-request allocation. */
    private val prefixParts: List<String> = splitPath(prefix)

    // Use CopyOnWriteArrayList for thread-safe registration from concurrent module installs.
    // After freezeMiddleware() the router is only read, so COW overhead is irrelevant at runtime.
    private val routes = java.util.concurrent.CopyOnWriteArrayList<RouteEntry>()
    private val children = java.util.concurrent.CopyOnWriteArrayList<Router>()
    private val webSocketRoutes = java.util.concurrent.CopyOnWriteArrayList<WebSocketRouteEntry>()
    private val sseRoutes = java.util.concurrent.CopyOnWriteArrayList<SSERouteEntry>()
    private val staticHandlers = java.util.concurrent.CopyOnWriteArrayList<StaticHandler>()
    private var authConfig: AuthConfig? = null

    /** Registers a GET route handler at the given [path]. */
    fun get(path: String, handler: suspend RoutingContext.() -> Unit) {
        routes.add(RouteEntry(HttpMethod.Get, normalizePath(path), handler))
    }

    /** Registers a HEAD route handler at the given [path]. */
    fun head(path: String, handler: suspend RoutingContext.() -> Unit) {
        routes.add(RouteEntry(HttpMethod.Head, normalizePath(path), handler))
    }

    /** Registers a POST route handler at the given [path]. */
    fun post(path: String, handler: suspend RoutingContext.() -> Unit) {
        routes.add(RouteEntry(HttpMethod.Post, normalizePath(path), handler))
    }

    /** Registers a PUT route handler at the given [path]. */
    fun put(path: String, handler: suspend RoutingContext.() -> Unit) {
        routes.add(RouteEntry(HttpMethod.Put, normalizePath(path), handler))
    }

    /** Registers a DELETE route handler at the given [path]. */
    fun delete(path: String, handler: suspend RoutingContext.() -> Unit) {
        routes.add(RouteEntry(HttpMethod.Delete, normalizePath(path), handler))
    }

    /** Registers a PATCH route handler at the given [path]. */
    fun patch(path: String, handler: suspend RoutingContext.() -> Unit) {
        routes.add(RouteEntry(HttpMethod.Patch, normalizePath(path), handler))
    }

    /** Creates a nested route scope with the given [path] prefix. */
    fun route(path: String, block: Router.() -> Unit) {
        val child = Router(normalizePath(path))
        child.block()
        children.add(child)
    }

    /**
     * Creates an authentication scope that wraps all routes registered within the [block].
     * Routes inside will require authentication from the specified [providers].
     *
     * @param providers The authentication provider names to check (null entries allow anonymous fallback)
     * @param optional If true, authentication failure does not reject the request but leaves the principal as null
     */
    fun authenticate(vararg providers: String?, optional: Boolean = false, block: Router.() -> Unit) {
        val child = Router(prefix = "")
        child.authConfig = AuthConfig(providers.toList(), optional)
        child.block()
        children.add(child)
    }

    /** Registers a WebSocket endpoint at the given [path] with an optional [protocol]. */
    fun webSocket(path: String, protocol: String? = null, handler: suspend WebSocketSession.() -> Unit) {
        webSocketRoutes.add(WebSocketRouteEntry(normalizePath(path), protocol, handler))
    }

    /** Registers a Server-Sent Events endpoint within the current route scope. */
    fun sse(handler: suspend ServerSSESession.() -> Unit) {
        sseRoutes.add(SSERouteEntry("", handler))
    }

    /** Registers a Server-Sent Events endpoint at the given [path]. */
    fun sse(path: String, handler: suspend ServerSSESession.() -> Unit) {
        sseRoutes.add(SSERouteEntry(normalizePath(path), handler))
    }

    /**
     * Serves static files from the filesystem [directory] under the given URL [remotePath].
     *
     * When a GET request arrives whose path starts with [remotePath], the remaining path is
     * resolved relative to [directory] and the file is served with an appropriate content type.
     * This is intended for development mode, where files are read directly from the project
     * source tree for hot-reload convenience.
     *
     * @param remotePath the URL prefix that triggers static file serving (e.g., "/")
     * @param directory the local filesystem directory to serve files from
     */
    fun staticFiles(remotePath: String, directory: File, cacheControl: String? = null) {
        staticHandlers.add(StaticHandler(normalizePath(remotePath), StaticSource.FileSystem(directory), cacheControl))
    }

    /**
     * Serves static resources from the classpath under the given URL [remotePath].
     *
     * When a GET request arrives whose path starts with [remotePath], the remaining path is
     * resolved within the [resourcePackage] on the classpath and served with an appropriate
     * content type. This is the standard mechanism for serving bundled static assets in
     * production.
     *
     * @param remotePath the URL prefix that triggers static resource serving (e.g., "/")
     * @param resourcePackage the classpath directory containing the static resources (e.g., "static")
     */
    fun staticResources(remotePath: String, resourcePackage: String, cacheControl: String? = null) {
        staticHandlers.add(StaticHandler(normalizePath(remotePath), StaticSource.Classpath(resourcePackage), cacheControl))
    }

    private fun normalizePath(path: String): String {
        return "/" + path.split("/").filter { it.isNotEmpty() }.joinToString("/")
    }

    /**
     * Resolves the best matching route for the given [method] and [path].
     * Returns a [ResolvedRoute] with the handler and extracted path parameters, or null if no match.
     *
     * Regular routes are checked first, then child routers, and finally static file/resource
     * handlers as a fallback for GET requests. A HEAD route registered anywhere in the tree,
     * including a later child router, takes precedence over a GET route used as a HEAD fallback.
     */
    suspend fun resolve(method: HttpMethod, path: String): ResolvedRoute? {
        if (method == HttpMethod.Head) {
            resolve(method, path, headFallback = false)?.let { return it }
        }
        return resolve(method, path, headFallback = true)
    }

    private suspend fun resolve(method: HttpMethod, path: String, headFallback: Boolean): ResolvedRoute? {
        var pathParts: List<String>? = null

        // Check direct routes using pre-split parts for efficient matching
        for (entry in routes) {
            if (entry.method == method) {
                val params = if ('{' !in entry.path) {
                    if (entry.path == path) {
                        emptyMap()
                    } else {
                        matchPath(entry.pathParts, pathParts ?: splitPath(path).also { pathParts = it })
                    }
                } else {
                    matchPath(entry.pathParts, pathParts ?: splitPath(path).also { pathParts = it })
                }
                if (params != null) {
                    return ResolvedRoute(entry.handler, params, authConfig, routePattern = entry.path)
                }
            }
        }

        // HEAD requests fall back to matching GET handlers per HTTP semantics
        if (method == HttpMethod.Head && headFallback) {
            for (entry in routes) {
                if (entry.method == HttpMethod.Get) {
                    val params = if ('{' !in entry.path) {
                        if (entry.path == path) {
                            emptyMap()
                        } else {
                            matchPath(entry.pathParts, pathParts ?: splitPath(path).also { pathParts = it })
                        }
                    } else {
                        matchPath(entry.pathParts, pathParts ?: splitPath(path).also { pathParts = it })
                    }
                    if (params != null) {
                        return ResolvedRoute(entry.handler, params, authConfig, routePattern = entry.path)
                    }
                }
            }
        }

        // Check children using pre-split prefix parts
        for (child in children) {
            val parts = pathParts ?: splitPath(path).also { pathParts = it }
            val childPath = if (child.prefix.isNotEmpty()) {
                val prefixMatch = matchPathPrefix(child.prefixParts, parts)
                if (prefixMatch != null) prefixMatch else continue
            } else {
                path to emptyMap()
            }

            val result = child.resolve(method, childPath.first, headFallback)
            if (result != null) {
                val mergedParams = childPath.second + result.pathParameters
                val mergedAuth = result.authConfig ?: child.authConfig
                return ResolvedRoute(result.handler, mergedParams, mergedAuth)
            }
        }

        // Check static handlers as a fallback for GET and HEAD requests
        if (method == HttpMethod.Get || (method == HttpMethod.Head && headFallback)) {
            for (handler in staticHandlers) {
                val relativePath = extractRelativePath(handler.remotePath, path) ?: continue
                val staticRoute = resolveStaticHandler(handler, relativePath)
                if (staticRoute != null) return staticRoute
            }
        }

        return null
    }

    /**
     * Returns the set of HTTP methods that are registered for the given [path],
     * used to populate the Allow header in 405 Method Not Allowed responses.
     * Returns an empty set if no routes match the path at all.
     */
    fun allowedMethods(path: String): Set<HttpMethod> {
        val pathParts = splitPath(path)
        val methods = mutableSetOf<HttpMethod>()

        for (entry in routes) {
            val params = matchPath(entry.pathParts, pathParts)
            if (params != null) methods.add(entry.method)
        }

        for (child in children) {
            val childPath = if (child.prefix.isNotEmpty()) {
                matchPathPrefix(child.prefixParts, pathParts) ?: continue
            } else {
                path to emptyMap()
            }
            methods.addAll(child.allowedMethods(childPath.first))
        }

        return methods
    }

    /**
     * Extracts the relative resource path from a request path after stripping the static
     * handler's prefix. Returns null if the request path does not start with the prefix.
     */
    private fun extractRelativePath(remotePath: String, requestPath: String): String? {
        val normalizedRemote = remotePath.trimEnd('/')
        val normalizedRequest = requestPath.trimEnd('/')
        return if (normalizedRemote == "/" || normalizedRemote.isEmpty()) {
            requestPath
        } else if (normalizedRequest.startsWith(normalizedRemote)) {
            val remaining = requestPath.removePrefix(normalizedRemote)
            // Ensure the match is at a path boundary (next char must be '/' or path is exact)
            if (remaining.isEmpty() || remaining.startsWith("/")) remaining else null
        } else {
            null
        }
    }

    /**
     * Creates a [ResolvedRoute] for a static handler if the target resource or file exists.
     * The generated handler reads the resource bytes and responds with the appropriate content type.
     */
    private suspend fun resolveStaticHandler(handler: StaticHandler, relativePath: String): ResolvedRoute? {
        val cleanPath = relativePath.removePrefix("/").trimEnd('/')

        // When the path is empty (root request), try serving index.html as a default document
        val candidates = if (cleanPath.isEmpty()) listOf("index.html") else listOf(cleanPath)

        return when (val source = handler.source) {
            is StaticSource.Classpath -> {
                for (candidate in candidates) {
                    val resourcePath = "${source.packagePath}/$candidate"
                    // Normalize first, then verify the resolved path stays within the resource
                    // package. This catches encoded traversal attempts (e.g., %2e%2e) that
                    // survive URL decoding and simple string checks.
                    val normalized = java.nio.file.Paths.get(resourcePath).normalize().toString().replace('\\', '/')
                    if (!normalized.startsWith(source.packagePath)) continue
                    val classLoader = Thread.currentThread().contextClassLoader ?: this::class.java.classLoader
                    val resource = classLoader.getResource(normalized) ?: continue
                    val contentType = contentTypeForPath(candidate)
                    return ResolvedRoute(
                        handler = {
                            handler.cacheControl?.let { call.response.header(HttpHeaders.CacheControl, it) }
                            call.respondStreaming(contentType) { stream ->
                                withContext(Dispatchers.IO) { resource.openStream().use { stream.copyFrom(it) } }
                            }
                        },
                        pathParameters = emptyMap(),
                        authConfig = null
                    )
                }
                null
            }

            is StaticSource.FileSystem -> {
                for (candidate in candidates) {
                    val file = withContext(Dispatchers.IO) {
                        val file = File(source.directory, candidate)
                        if (!file.isFile || !file.canonicalPath.startsWith(source.canonicalPath)) {
                            return@withContext null
                        }
                        file
                    }
                    if (file == null) continue
                    val contentType = contentTypeForPath(candidate)
                    return ResolvedRoute(
                        handler = {
                            val (length, lastModified) = withContext(Dispatchers.IO) { file.length() to file.lastModified() }
                            // mtime+size as the validator: exact across rapid dev-loop rebuilds
                            // (Last-Modified alone has one-second granularity). Lets a revalidating
                            // client (Cache-Control: no-cache) skip the body with a 304 instead of
                            // re-downloading an unchanged file on every page load.
                            val etag = "\"$lastModified-$length\""
                            handler.cacheControl?.let { call.response.header(HttpHeaders.CacheControl, it) }
                            call.response.header(HttpHeaders.ETag, etag)
                            val ifNoneMatch = call.request.header(HttpHeaders.IfNoneMatch)
                            if (ifNoneMatch != null && (ifNoneMatch == "*" || ifNoneMatch.split(",").any { it.trim() == etag })) {
                                call.respond(HttpStatusCode.NotModified)
                            } else {
                                call.response.header(HttpHeaders.ContentLength, length.toString())
                                // No time limit: static files can be large (media); a client that stops
                                // reading altogether is ended by the streaming response's stall timeout.
                                call.respondStreaming(contentType, HttpStatusCode.OK, timeLimit = null) { stream ->
                                    withContext(Dispatchers.IO) { file.inputStream().use { stream.copyFrom(it) } }
                                }
                            }
                        },
                        pathParameters = emptyMap(),
                        authConfig = null
                    )
                }
                null
            }
        }
    }

    /**
     * Resolves a WebSocket route for the given [path].
     * Returns the matching [WebSocketRouteEntry] or null.
     */
    fun resolveWebSocket(path: String): WebSocketRouteEntry? {
        for (entry in webSocketRoutes) {
            val params = matchPath(entry.path, path)
            if (params != null) {
                return entry.copy(authConfig = entry.authConfig ?: authConfig, pathParameters = params)
            }
        }
        for (child in children) {
            val childPath = if (child.prefix.isNotEmpty()) {
                matchPathPrefix(child.prefix, path) ?: continue
            } else {
                path to emptyMap()
            }
            val result = child.resolveWebSocket(childPath.first)
            if (result != null) {
                val mergedParams = childPath.second + (result.pathParameters ?: emptyMap())
                return result.copy(
                    authConfig = result.authConfig ?: child.authConfig,
                    pathParameters = mergedParams,
                )
            }
        }
        return null
    }

    /**
     * Resolves an SSE route for the given [path].
     * Returns the matching [SSERouteEntry] or null.
     */
    fun resolveSSE(path: String): SSERouteEntry? {
        for (entry in sseRoutes) {
            // Parent routers have already consumed this router's prefix before recursing.
            // A pathless SSE registration therefore matches the child router's root.
            val entryPath = if (entry.path.isEmpty()) "/" else entry.path
            val params = matchPath(entryPath, path)
            if (params != null) {
                return entry.copy(authConfig = entry.authConfig ?: authConfig, pathParameters = params)
            }
        }
        for (child in children) {
            val childPath = if (child.prefix.isNotEmpty()) {
                matchPathPrefix(child.prefix, path) ?: continue
            } else {
                path to emptyMap()
            }
            val result = child.resolveSSE(childPath.first)
            if (result != null) {
                val mergedParams = childPath.second + (result.pathParameters ?: emptyMap())
                return result.copy(
                    authConfig = result.authConfig ?: child.authConfig,
                    pathParameters = mergedParams,
                )
            }
        }
        return null
    }

    /**
     * Recursively collects descriptions of all registered routes across the router tree.
     * Used by JMX introspection to enumerate routes without exposing internal data structures.
     *
     * @param parentPath the accumulated path prefix from parent routers in the tree
     * @return a flat list of [RouteDescription] entries for every HTTP, WebSocket, SSE,
     *         and static route registered in this router and all its children
     */
    fun collectRouteDescriptions(parentPath: String = ""): List<RouteDescription> {
        val result = mutableListOf<RouteDescription>()
        val currentPrefix = if (prefix.isNotEmpty() && prefix != "/") parentPath + prefix else parentPath
        val isAuthenticated = authConfig != null

        for (entry in routes) {
            result.add(
                RouteDescription(
                    type = RouteType.HTTP,
                    path = currentPrefix + entry.path,
                    method = entry.method.value,
                    authenticated = isAuthenticated,
                )
            )
        }

        for (entry in webSocketRoutes) {
            result.add(
                RouteDescription(
                    type = RouteType.WEBSOCKET,
                    path = currentPrefix + entry.path,
                    authenticated = isAuthenticated,
                )
            )
        }

        for (entry in sseRoutes) {
            val ssePath = if (entry.path.isEmpty()) currentPrefix else currentPrefix + entry.path
            result.add(
                RouteDescription(
                    type = RouteType.SSE,
                    path = ssePath,
                    authenticated = isAuthenticated,
                )
            )
        }

        for (handler in staticHandlers) {
            val sourceType = when (handler.source) {
                is StaticSource.Classpath -> "classpath"
                is StaticSource.FileSystem -> "filesystem"
            }
            result.add(
                RouteDescription(
                    type = RouteType.STATIC,
                    path = currentPrefix + handler.remotePath,
                    staticSourceType = sourceType,
                )
            )
        }

        for (child in children) {
            result.addAll(child.collectRouteDescriptions(currentPrefix))
        }

        return result
    }

    companion object {
        private val log = LoggerFactory.getLogger(Router::class.java)

        /** Splits a path into non-empty segments. */
        fun splitPath(path: String): List<String> {
            var result: MutableList<String>? = null
            var segmentStart = 0
            for (index in path.indices) {
                if (path[index] != '/') continue
                if (index > segmentStart) {
                    if (result == null) result = ArrayList(4)
                    result.add(path.substring(segmentStart, index))
                }
                segmentStart = index + 1
            }
            if (segmentStart < path.length) {
                if (result == null) result = ArrayList(4)
                result.add(path.substring(segmentStart))
            }
            return result ?: emptyList()
        }

        /**
         * Matches a single pattern segment against a request path segment, extracting
         * any embedded path parameter. Returns the parameter name and value, or null
         * if the segment does not match.
         *
         * Supports three segment forms:
         * - `{name}` — full segment parameter
         * - `prefix{name}suffix` — partial parameter (e.g., `@{scope}`, `v{version}.tar`)
         * - literal — exact match required
         */
        private fun matchSegment(pattern: String, segment: String): Pair<String, String>? {
            if (pattern.startsWith("{") && pattern.endsWith("}")) {
                return pattern.substring(1, pattern.length - 1) to segment
            }
            val braceStart = pattern.indexOf('{')
            val braceEnd = pattern.indexOf('}')
            if (braceStart >= 0 && braceEnd > braceStart) {
                val prefix = pattern.substring(0, braceStart)
                val suffix = pattern.substring(braceEnd + 1)
                if (!segment.startsWith(prefix) || !segment.endsWith(suffix)) return null
                if (segment.length < prefix.length + suffix.length) return null
                val paramName = pattern.substring(braceStart + 1, braceEnd)
                return paramName to segment.substring(prefix.length, segment.length - suffix.length)
            }
            return if (pattern == segment) LITERAL_MATCH else null
        }

        /** Sentinel pair used by [matchSegment] for literal matches that produce no parameter. */
        private val LITERAL_MATCH: Pair<String, String> = "" to ""

        /**
         * Matches a route pattern against a request path, extracting path parameters.
         * Returns a map of parameter names to values, or null if no match.
         *
         * Supports tailcard parameters: when the last pattern segment has the form `{name...}`,
         * it consumes all remaining request path segments joined with `/`. This is used for
         * routes like `/maven/{path...}` where the path depth is variable.
         *
         * @param patternParts pre-split pattern segments (use [splitPath] at registration time)
         */
        fun matchPath(patternParts: List<String>, pathParts: List<String>): Map<String, String>? {
            val lastPattern = patternParts.lastOrNull()
            val isTailcard = lastPattern != null && lastPattern.startsWith("{") && lastPattern.endsWith("...}")

            if (isTailcard) {
                val fixedCount = patternParts.size - 1
                if (pathParts.size < fixedCount) return null

                var params: MutableMap<String, String>? = null
                for (i in 0 until fixedCount) {
                    val result = matchSegment(patternParts[i], pathParts[i]) ?: return null
                    if (result !== LITERAL_MATCH) {
                        if (params == null) params = mutableMapOf()
                        params[result.first] = result.second
                    }
                }
                val tailcardName = lastPattern.substring(1, lastPattern.length - 4)
                if (params == null) params = mutableMapOf()
                params[tailcardName] = pathParts.drop(fixedCount).joinToString("/")
                return params
            }

            if (patternParts.size != pathParts.size) return null

            var params: MutableMap<String, String>? = null
            for (i in patternParts.indices) {
                val result = matchSegment(patternParts[i], pathParts[i]) ?: return null
                if (result !== LITERAL_MATCH) {
                    if (params == null) params = mutableMapOf()
                    params[result.first] = result.second
                }
            }
            return params ?: emptyMap()
        }

        /**
         * Matches a route pattern against a request path, extracting path parameters.
         * Returns a map of parameter names to values, or null if no match.
         */
        fun matchPath(pattern: String, path: String): Map<String, String>? {
            return matchPath(splitPath(pattern), splitPath(path))
        }

        /**
         * Matches a route prefix pattern against the beginning of a request path.
         * Returns the remaining path and extracted parameters, or null if no match.
         *
         * @param prefixParts pre-split prefix segments (use [splitPath] at registration time)
         */
        fun matchPathPrefix(prefixParts: List<String>, pathParts: List<String>): Pair<String, Map<String, String>>? {
            if (pathParts.size < prefixParts.size) return null

            val params = mutableMapOf<String, String>()
            for (i in prefixParts.indices) {
                val result = matchSegment(prefixParts[i], pathParts[i]) ?: return null
                if (result !== LITERAL_MATCH) {
                    params[result.first] = result.second
                }
            }

            val remaining = "/" + pathParts.drop(prefixParts.size).joinToString("/")
            return remaining to params
        }

        /**
         * Matches a route prefix pattern against the beginning of a request path.
         * Returns the remaining path and extracted parameters, or null if no match.
         */
        fun matchPathPrefix(prefix: String, path: String): Pair<String, Map<String, String>>? {
            return matchPathPrefix(splitPath(prefix), splitPath(path))
        }
    }
}

/**
 * A registered HTTP route with its method, path pattern, and handler function.
 */
data class RouteEntry(
    val method: HttpMethod,
    val path: String,
    val handler: suspend RoutingContext.() -> Unit,
    /** Pre-split path segments for efficient matching without per-request allocation. */
    val pathParts: List<String> = Router.splitPath(path),
)

/**
 * A registered WebSocket route with its path, optional subprotocol, handler function, and
 * optional authentication configuration inherited from the enclosing [Router] scope.
 */
data class WebSocketRouteEntry(
    val path: String,
    val protocol: String?,
    val handler: suspend WebSocketSession.() -> Unit,
    val authConfig: AuthConfig? = null,
    val pathParameters: Map<String, String>? = null,
)

/**
 * A registered Server-Sent Events route with its path, handler function, and
 * optional authentication configuration inherited from the enclosing [Router] scope.
 */
data class SSERouteEntry(
    val path: String,
    val handler: suspend ServerSSESession.() -> Unit,
    val authConfig: AuthConfig? = null,
    val pathParameters: Map<String, String>? = null,
)

/**
 * Authentication configuration for a route scope, specifying which providers
 * to check and whether authentication is optional.
 */
data class AuthConfig(
    val providers: List<String?>,
    val optional: Boolean
)

/**
 * The result of route resolution, containing the matched handler, extracted path parameters,
 * and any authentication configuration that applies to the route.
 */
data class ResolvedRoute(
    val handler: suspend RoutingContext.() -> Unit,
    val pathParameters: Map<String, String>,
    val authConfig: AuthConfig?,
    val routePattern: String? = null,
)

/**
 * Describes a static content handler that maps a URL prefix to either a filesystem directory
 * or a classpath resource package.
 */
internal data class StaticHandler(
    val remotePath: String,
    val source: StaticSource,
    /** Optional `Cache-Control` header value for everything this handler serves (e.g. fonts). */
    val cacheControl: String? = null,
)

/**
 * The source for static content, either a local filesystem directory for development
 * or a classpath resource package for production.
 */
internal sealed class StaticSource {
    /** Serves files from a classpath resource directory. */
    data class Classpath(val packagePath: String) : StaticSource()

    /** Serves files from a local filesystem directory. The canonical path is pre-computed to avoid per-request I/O. */
    data class FileSystem(val directory: File) : StaticSource() {
        val canonicalPath: String = directory.canonicalPath
    }
}

/** Pre-allocated content types for static file serving to avoid per-request allocation. */
private object StaticContentTypes {
    val Svg = ContentType("image", "svg+xml")
    val Ico = ContentType("image", "x-icon")
    val Woff = ContentType("font", "woff")
    val Woff2 = ContentType("font", "woff2")
    val Ttf = ContentType("font", "ttf")
    val Otf = ContentType("font", "otf")
    val Eot = ContentType("application", "vnd.ms-fontobject")
    val Webp = ContentType("image", "webp")
    val Avif = ContentType("image", "avif")
    val Mp4 = ContentType("video", "mp4")
    val Webm = ContentType("video", "webm")
    val Pdf = ContentType("application", "pdf")
}

/**
 * Determines the appropriate [ContentType] for a file based on its extension.
 * Falls back to [ContentType.Application.OctetStream] for unrecognized extensions.
 */
internal fun contentTypeForPath(path: String): ContentType {
    val extension = path.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "html", "htm" -> ContentType.Text.Html
        "css" -> ContentType.Text.Css
        "js", "mjs" -> ContentType.Text.JavaScript
        "json" -> ContentType.Application.Json
        "xml" -> ContentType.Application.Xml
        "txt" -> ContentType.Text.Plain
        "svg" -> StaticContentTypes.Svg
        "png" -> ContentType.Image.Png
        "jpg", "jpeg" -> ContentType.Image.Jpeg
        "gif" -> ContentType.Image.Gif
        "ico" -> StaticContentTypes.Ico
        "woff" -> StaticContentTypes.Woff
        "woff2" -> StaticContentTypes.Woff2
        "ttf" -> StaticContentTypes.Ttf
        "otf" -> StaticContentTypes.Otf
        "eot" -> StaticContentTypes.Eot
        "webp" -> StaticContentTypes.Webp
        "avif" -> StaticContentTypes.Avif
        "mp4" -> StaticContentTypes.Mp4
        "webm" -> StaticContentTypes.Webm
        "pdf" -> StaticContentTypes.Pdf
        "map" -> ContentType.Application.Json
        else -> ContentType.Application.OctetStream
    }
}
