package bosca.server

import bosca.server.content.MultiPartData
import bosca.server.content.PartData
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpHeaders as NettyHttpHeaders
import io.netty.handler.codec.http.LastHttpContent
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.handler.codec.http.multipart.Attribute
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory
import io.netty.handler.codec.http.multipart.FileUpload
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job

/**
 * Wraps a Netty [HttpRequest] and an optional streaming [RequestBody] to provide a
 * higher-level API for reading HTTP request properties such as headers, query parameters,
 * path, method, cookies, and body content.
 *
 * For bodyless requests (GET, HEAD, DELETE, OPTIONS), [body] is null and no queue
 * infrastructure is allocated, keeping overhead minimal for the common case.
 *
 * For requests with a body, chunks are delivered asynchronously by Netty and consumed
 * via suspending functions ([bodyBytes], [bodyText], [receiveMultipart]).
 *
 * This is the primary request abstraction used by route handlers and middleware throughout
 * the server framework.
 */
class ServerRequest internal constructor(
    private val httpRequest: HttpRequest,
    private val body: RequestBody?,
    /** The remote IP address of the client, derived from the Netty channel's remote socket address. */
    val remoteAddress: String? = null,
    /**
     * Whether to trust X-Forwarded-* headers for origin resolution.
     * Should only be enabled when the server is behind a trusted reverse proxy
     * that strips client-supplied forwarded headers.
     */
    val trustForwardedHeaders: Boolean = true,
    /** The already-parsed method, when the server has one; otherwise parsed on first access. */
    parsedMethod: HttpMethod? = null,
) {

    private val bodyConsumed = AtomicBoolean(false)

    /** Marks the body as consumed, throwing if it has already been consumed. */
    private fun markBodyConsumed() {
        if (!bodyConsumed.compareAndSet(false, true)) {
            throw IllegalStateException("Request body has already been consumed")
        }
    }

    /** The raw URI from the request line, including query string. */
    val uri: String get() = httpRequest.uri()

    @Volatile
    private var cachedHttpMethod: HttpMethod? = parsedMethod

    /** The HTTP method of this request. */
    val httpMethod: HttpMethod
        get() = cachedHttpMethod ?: HttpMethod.parse(httpRequest.method().name()).also { cachedHttpMethod = it }

    @Volatile
    private var cachedPath: String? = null

    /** The request path without query string. */
    val path: String
        get() = cachedPath ?: uri.let { value ->
            val idx = value.indexOf('?')
            if (idx >= 0) value.substring(0, idx) else value
        }.also { cachedPath = it }

    @Volatile
    private var cachedQueryParameters: Parameters? = null

    /** Parsed query string parameters. */
    val queryParameters: Parameters
        get() = cachedQueryParameters ?: QueryStringDecoder(uri).let { decoder ->
            Parameters(decoder.parameters())
        }.also { cachedQueryParameters = it }

    /** Access to request headers. */
    val headers: RequestHeaders = RequestHeaders(httpRequest.headers())

    @Volatile
    private var cachedCookies: RequestCookies? = null

    /** Access to request cookies parsed from all Cookie headers. */
    val cookies: RequestCookies
        get() = cachedCookies ?: RequestCookies(headers.getAll(HttpHeaders.Cookie)).also { cachedCookies = it }

    /**
     * Reads the raw request body as a byte array, suspending until all chunks have arrived.
     *
     * Suitable for small payloads like JSON or form data. For large uploads, use
     * [receiveMultipart] instead.
     *
     * Returns an empty array for bodyless requests (GET, HEAD, etc.).
     *
     * This consumes the body — only one of [bodyBytes] or [receiveMultipart]
     * can be called per request.
     */
    suspend fun bodyBytes(): ByteArray {
        markBodyConsumed()
        return body?.readAllBytes() ?: ByteArray(0)
    }

    /**
     * Streams the request body chunk by chunk into the given [output] without
     * accumulating the entire body in memory. Returns the total bytes written.
     *
     * Use this for large uploads where buffering the full body would cause
     * excessive heap pressure.
     *
     * Returns 0 for bodyless requests. This consumes the body — only one of
     * [bodyBytes], [bodyStreamTo], or [receiveMultipart] can be called.
     */
    suspend fun bodyStreamTo(output: OutputStream): Long {
        markBodyConsumed()
        return body?.streamTo(output) ?: 0L
    }

    /**
     * Streams the request body into [output] like [bodyStreamTo], running each blocking write on
     * [writeDispatcher]. Use it when [output] blocks until another task consumes it, such as a
     * pipe, so a waiting write never holds a thread that the consumer's side depends on.
     */
    suspend fun bodyStreamTo(output: OutputStream, writeDispatcher: CoroutineDispatcher): Long {
        markBodyConsumed()
        return body?.streamTo(output, writeDispatcher) ?: 0L
    }

    /**
     * Returns the request body as a blocking [InputStream], for a blocking library that reads one
     * on a thread of its own (never an event loop). Each read blocks until data arrives. Cancelling
     * the calling coroutine fails a waiting read with a [kotlinx.coroutines.CancellationException].
     * Closing the stream discards the rest of the body. A read on the channel's event loop throws
     * [IllegalStateException] instead of blocking the loop that has to deliver the data.
     *
     * Returns an empty stream for bodyless requests. This consumes the body.
     */
    suspend fun bodyInputStream(): InputStream {
        markBodyConsumed()
        return body?.inputStream(currentCoroutineContext().job) ?: InputStream.nullInputStream()
    }

    /**
     * Reads the request body as a UTF-8 string, suspending until all chunks have arrived.
     *
     * Returns an empty string for bodyless requests.
     */
    suspend fun bodyText(): String = String(bodyBytes(), StandardCharsets.UTF_8)

    /** The Content-Type of the request, parsed from the header. */
    fun contentType(): ContentType? {
        val header = headers[HttpHeaders.ContentType] ?: return null
        return ContentType.parse(header)
    }

    /** Returns the first value of the Accept-Language header, or null. */
    fun acceptLanguage(): String? = headers[HttpHeaders.AcceptLanguage]

    /**
     * Parses the Accept-Language header into a list of language-quality pairs,
     * sorted by quality factor descending.
     */
    fun acceptLanguageItems(): List<LanguageItem> {
        val header = headers[HttpHeaders.AcceptLanguage] ?: return emptyList()
        return header.split(",").map { part ->
            val segments = part.trim().split(";")
            val language = segments[0].trim()
            val quality = segments.getOrNull(1)?.trim()?.removePrefix("q=")?.toFloatOrNull() ?: 1.0f
            LanguageItem(language, quality)
        }.sortedByDescending { it.quality }
    }

    /** Returns the value of the specified request header, or null if not present. */
    fun header(name: String): String? = headers[name]

    /**
     * Parses the request body as URL-encoded form parameters, suspending until the body
     * has been fully received. Used for HTML form submissions with content type
     * application/x-www-form-urlencoded.
     */
    suspend fun receiveFormParameters(): Parameters {
        val text = bodyText()
        if (text.isBlank()) return Parameters.Empty
        val pairs = text.split("&").map { pair ->
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) {
                URLDecoder.decode(parts[0], StandardCharsets.UTF_8) to
                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
            } else {
                // Per HTML spec, a key with no '=' is treated as having an empty value
                URLDecoder.decode(parts[0], StandardCharsets.UTF_8) to ""
            }
        }
        return Parameters.fromPairs(pairs)
    }

    /**
     * Parses the request body as multipart form data using Netty's [HttpPostRequestDecoder]
     * with incremental chunk feeding. Each body chunk is offered to the decoder as it arrives
     * via a suspending consume loop, allowing arbitrarily large uploads without buffering the
     * entire request in memory.
     *
     * Large file uploads are automatically spilled to disk by the data factory when they
     * exceed the minimum size threshold, preventing out-of-memory errors.
     *
     * This consumes the body — only one of [bodyBytes] or [receiveMultipart]
     * can be called per request.
     */
    suspend fun receiveMultipart(): MultiPartData {
        markBodyConsumed()
        val factory = DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE)
        val decoder = HttpPostRequestDecoder(factory, httpRequest)
        try {
            body?.consumeChunks { chunk ->
                decoder.offer(chunk)
            } ?: decoder.offer(LastHttpContent.EMPTY_LAST_CONTENT)
        } catch (@Suppress("SwallowedException") _: HttpPostRequestDecoder.EndOfDataDecoderException) {
            // Expected when the decoder reaches the end of the data stream
        } catch (e: Exception) {
            decoder.destroy()
            throw e
        }
        val parts = try {
            extractParts(decoder)
        } catch (e: Exception) {
            decoder.destroy()
            throw e
        }
        // Decoder is NOT destroyed here — individual FileUploadItem.dispose() calls
        // release each Netty data object. Eagerly destroying the decoder would free
        // file data before callers can read it.
        return MultiPartData(parts)
    }

    private fun extractParts(decoder: HttpPostRequestDecoder): List<PartData> {
        val parts = mutableListOf<PartData>()
        for (data in decoder.bodyHttpDatas) {
            when (data) {
                is Attribute -> {
                    parts.add(PartData.FormItem(data.name, data.value))
                }
                is FileUpload -> {
                    parts.add(PartData.FileUploadItem(data))
                }
            }
        }
        return parts
    }

    @Volatile
    private var clientIpResolved = false
    private var cachedClientIp: String? = null

    /**
     * The client IP address, resolved from X-Forwarded-For or X-Real-IP headers when
     * [trustForwardedHeaders] is enabled, falling back to [remoteAddress].
     *
     * Only the first address in X-Forwarded-For is used (the original client IP).
     * Returns null only when no IP source is available.
     */
    val clientIp: String?
        get() {
            if (clientIpResolved) return cachedClientIp
            val resolved = resolveClientIp()
            cachedClientIp = resolved
            clientIpResolved = true
            return resolved
        }

    private fun resolveClientIp(): String? {
        if (trustForwardedHeaders) {
            val xForwardedFor = headers[HttpHeaders.XForwardedFor]
            if (!xForwardedFor.isNullOrBlank()) {
                // X-Forwarded-For: client, proxy1, proxy2 — take the first (leftmost) IP
                return xForwardedFor.substringBefore(',').trim().ifBlank { null }
            }
            val xRealIp = headers[HttpHeaders.XRealIp]
            if (!xRealIp.isNullOrBlank()) {
                return xRealIp.trim()
            }
        }
        return remoteAddress
    }

    @Volatile
    private var cachedOrigin: RequestOrigin? = null

    /**
     * The originating host information, taking into account forwarded headers
     * when behind a reverse proxy.
     */
    val origin: RequestOrigin
        get() = cachedOrigin ?: resolveOrigin().also { cachedOrigin = it }

    private fun resolveOrigin(): RequestOrigin {
        val forwardedProto = if (trustForwardedHeaders) headers[HttpHeaders.XForwardedProto] else null
        val forwardedHost = if (trustForwardedHeaders) headers[HttpHeaders.XForwardedHost] else null
        val forwardedPort = if (trustForwardedHeaders) headers[HttpHeaders.XForwardedPort]?.toIntOrNull() else null
        val rawHost = forwardedHost ?: headers[HttpHeaders.Host] ?: "localhost"
        val scheme = forwardedProto ?: "http"
        // Parse host:port from the Host header value, handling IPv6 bracket notation
        val (host, parsedPort) = if (rawHost.startsWith("[")) {
            // IPv6 bracket notation: [::1]:8080 or [::1]
            val closeBracket = rawHost.indexOf(']')
            if (closeBracket < 0) {
                rawHost to null
            } else {
                val ipv6Host = rawHost.substring(1, closeBracket)
                val afterBracket = rawHost.substring(closeBracket + 1)
                val port = if (afterBracket.startsWith(":")) {
                    afterBracket.substring(1).toIntOrNull()
                } else {
                    null
                }
                ipv6Host to port
            }
        } else {
            val colonIndex = rawHost.lastIndexOf(':')
            if (colonIndex > 0 && colonIndex < rawHost.length - 1) {
                val portStr = rawHost.substring(colonIndex + 1)
                val port = portStr.toIntOrNull()
                if (port != null) rawHost.substring(0, colonIndex) to port else rawHost to null
            } else {
                rawHost to null
            }
        }
        val port = forwardedPort ?: parsedPort ?: if (scheme == "https") 443 else 80
        return RequestOrigin(scheme, host, port)
    }

    @Volatile
    private var cachedAppOrigin: String? = null

    /**
     * The web origin (`scheme://host[:port]`) of the app the user is actually on, for building links in
     * transactional auth emails (verification / reset / account-link).
     *
     * Prefers the browser-supplied `Origin` header, then the `Referer` host, and only then [origin]. In
     * Bosca's split-host deployments the Studio app (e.g. `studio.example.com`) proxies its API calls to a
     * different API host (e.g. the apex `example.com`), so [origin] — derived from `Host` /
     * `X-Forwarded-Host` — reports the API host the request was *addressed to*, not the Studio host the
     * user *came from*. The `Origin`/`Referer` headers survive that proxy hop and name the real Studio
     * host. Non-browser callers (native apps, server-to-server) send neither, so this falls back to
     * [origin] unchanged — preserving the prior behavior.
     *
     * SECURITY: `Origin` / `Referer` are client-supplied, so the result MUST be validated against an
     * allow-list before it is used to build a link — see [bosca.security.service.AppOrigins.resolve], the
     * single chokepoint every caller routes through. This accessor only surfaces the candidate; it does
     * not validate.
     */
    val appOrigin: String
        get() = cachedAppOrigin ?: resolveAppOrigin().also { cachedAppOrigin = it }

    private fun resolveAppOrigin(): String {
        fun originOf(url: String): String? = try {
            val uri = URI(url)
            uri.host?.let { host ->
                val scheme = uri.scheme ?: return@let null
                // Omit the scheme's default port (443 for https, 80 otherwise) so a browser-sent
                // ":443"/":80" normalizes identically to [RequestOrigin.toOrigin], which the
                // app-origin allow-list entries are compared against.
                val defaultPort = if (scheme.equals("https", ignoreCase = true)) 443 else 80
                val port = if (uri.port != -1 && uri.port != defaultPort) ":${uri.port}" else ""
                "$scheme://$host$port"
            }
        } catch (_: Exception) {
            null
        }
        // "null" is the literal value browsers send for an opaque origin (e.g. sandboxed iframe); treat it
        // as absent so it falls through to Referer / the addressed origin rather than being parsed.
        return headers[HttpHeaders.Origin]?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }?.let(::originOf)
            ?: headers[HttpHeaders.Referer]?.takeIf { it.isNotBlank() }?.let(::originOf)
            ?: origin.toOrigin()
    }
}

/**
 * Represents a single language preference from the Accept-Language header with its quality factor.
 */
data class LanguageItem(val value: String, val quality: Float)

/**
 * Represents the originating connection information for a request, accounting for
 * reverse proxy forwarded headers.
 */
data class RequestOrigin(val scheme: String, val host: String, val port: Int) {
    /**
     * Renders this origin as `scheme://host[:port]`, omitting the port when it is the scheme's
     * default (443 for https, 80 otherwise). Suitable as the base for building absolute URLs.
     */
    fun toOrigin(): String {
        val defaultPort = if (scheme.equals("https", ignoreCase = true)) 443 else 80
        val portSuffix = if (port == defaultPort) "" else ":$port"
        return "$scheme://$host$portSuffix"
    }
}

/**
 * Provides read access to HTTP request headers from a Netty headers object,
 * implementing the framework [Headers] interface so it can be used directly
 * wherever [Headers] is expected.
 */
class RequestHeaders(private val nettyHeaders: NettyHttpHeaders) : Headers {

    /** Returns the first value for the given header [name], or null if not present. */
    override operator fun get(name: String): String? = nettyHeaders.get(name)

    /** Returns all values for the given header [name]. */
    override fun getAll(name: String): List<String> = nettyHeaders.getAll(name) ?: emptyList()

    /** Returns the set of all header names present. */
    override fun names(): Set<String> = nettyHeaders.names()

    /** Returns the header entries as a set of name to value-list mappings. */
    override fun entries(): Set<Map.Entry<String, List<String>>> =
        names().associateWith { name -> getAll(name) }.entries

    /** Returns true if there are no headers. */
    override fun isEmpty(): Boolean = nettyHeaders.isEmpty

    /** Iterates over all headers, invoking [action] for each name and its list of values. */
    override fun forEach(action: (String, List<String>) -> Unit) {
        for (name in names()) {
            action(name, getAll(name))
        }
    }

    /** Returns true if the given header [name] is present. */
    operator fun contains(name: String): Boolean = nettyHeaders.contains(name)
}
