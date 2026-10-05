package bosca.server

import bosca.core.annotations.Internal
import bosca.server.content.MultiPartData
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration

/**
 * Represents a single HTTP request-response exchange, providing access to the [request],
 * [response], path parameters, and authentication context.
 *
 * This is the primary abstraction passed to route handlers and middleware, replacing Ktor's
 * ApplicationCall with a direct Netty-backed implementation.
 */
class ServerCall(
    val request: ServerRequest,
    val response: ServerResponse,
    val pathParameters: Parameters = Parameters.Empty,
    val application: BoscaApplication,
) {
    init {
        response.onBeforeWrite {
            for (middleware in application.middleware) {
                middleware.onBeforeWrite(this)
            }
        }
    }

    /** Mutable attribute map for middleware to attach per-call data without coupling to specific types. */
    val attributes: MutableMap<String, Any> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        java.util.concurrent.ConcurrentHashMap()
    }

    /** Matched route template used for low-cardinality request telemetry. */
    internal var routePattern: String? = null

    /**
     * Tracks multipart data for auto-cleanup at end of request. Written by the handler and read
     * by the server's cleanup in the same call coroutine, so it needs no synchronization.
     */
    private var _multiPartData: MultiPartData? = null

    /** Authentication context for this call, populated by authentication middleware. */
    var authenticationContext: bosca.server.auth.CallAuthenticationContext = bosca.server.auth.CallAuthenticationContext()
        internal set

    /** Session data for this call, managed by session middleware. */
    val sessions: SessionManager = SessionManager(this)

    /**
     * Deserializes the request body into the specified type using the application's JSON serializer.
     * Supports both JSON and form-urlencoded content types.
     */
    suspend inline fun <reified T> receive(): T {
        val ct = request.contentType()
        if (ct != null && ct.match("application/x-www-form-urlencoded")) {
            throw UnsupportedOperationException("Use receiveParameters() for form data")
        }
        if (ct != null && ct.match("multipart/form-data")) {
            throw UnsupportedOperationException("Use receiveMultipart() for multipart data")
        }
        val body = request.bodyText()
        if (body.isEmpty()) {
            throw IllegalStateException("Request body is empty — cannot deserialize ${T::class.simpleName} from a ${request.httpMethod.value} request with no body")
        }
        return application.json.decodeFromString<T>(body)
    }

    /** Returns form parameters from the request body, suspending until the body is received. */
    suspend fun receiveParameters(): Parameters = request.receiveFormParameters()

    /**
     * Parses the request body as multipart form data, delegating to the underlying
     * Netty-based incremental multipart decoder. Body chunks are streamed to the decoder
     * as they arrive, and large file parts are spilled to disk to avoid excessive memory
     * consumption.
     *
     * @return a [MultiPartData] containing all decoded form fields and file uploads
     */
    suspend fun receiveMultipart(): MultiPartData {
        val data = request.receiveMultipart()
        _multiPartData = data
        return data
    }

    /** Disposes any multipart data that was received during this request. */
    internal fun cleanup() {
        _multiPartData?.close()
        _multiPartData = null
    }

    fun respond(status: HttpStatusCode) {
        response.respond(status)
    }

    /**
     * Sends a response with the given [message] serialized as JSON using the default 200 OK status.
     */
    suspend inline fun <reified T> respond(message: T) {
        respond<T>(HttpStatusCode.OK, message)
    }

    /**
     * Sends a response with the given [status] and [message].
     * If the message is a String, it is sent as plain text.
     * Otherwise, it is serialized as JSON.
     */
    suspend inline fun <reified T> respond(status: HttpStatusCode, message: T) {
        if (response.isCommitted) {
            error("Response already committed. $status - $message")
        }
        when (message) {
            is Unit -> {
                response.status(status)
                response.commit()
            }

            is String -> {
                response.respondText(message, ContentType.Text.Plain, status)
            }

            is ByteArray -> {
                response.respondBytes(message, ContentType.Application.OctetStream, status)
            }

            is HttpStatusCode -> {
                response.status(message)
                response.commit()
            }

            is ServerResponseContent -> {
                message.writeTo(this, status)
            }

            else -> {
                val text = application.json.encodeToString<T>(message)
                response.respondText(text, ContentType.Application.Json, status)
            }
        }
    }

    /**
     * Sends a response using an explicit [serializer], bypassing reified type resolution.
     * Use this when the compile-time type is erased (e.g., generic class type parameters)
     * and the caller already knows the correct serializer.
     *
     * The [serializer] may be null for types handled directly by the response dispatch
     * (Unit, String, ByteArray, HttpStatusCode, ServerResponseContent). For all other types
     * a non-null serializer is required or an [IllegalStateException] is thrown.
     */
    suspend fun <T> respond(message: T, serializer: KSerializer<T>?) {
        respond(HttpStatusCode.OK, message, serializer)
    }

    /**
     * Sends a response with the given [status] using an explicit [serializer].
     * Dispatches on runtime type for Unit, String, ByteArray, HttpStatusCode, and
     * ServerResponseContent; all other types are serialized as JSON using the [serializer].
     */
    suspend fun <T> respond(status: HttpStatusCode, message: T, serializer: KSerializer<T>?) {
        if (response.isCommitted) {
            error("Response already committed.")
        }
        when (message) {
            is Unit -> {
                response.status(status)
                response.commit()
            }

            is String -> {
                response.respondText(message, ContentType.Text.Plain, status)
            }

            is ByteArray -> {
                response.respondBytes(message, ContentType.Application.OctetStream, status)
            }

            is HttpStatusCode -> {
                response.status(message)
                response.commit()
            }

            is ServerResponseContent -> {
                message.writeTo(this, status)
            }

            is JsonElement -> {
                val text = message.toString()
                response.respondText(text, ContentType.Application.Json, status)
            }

            else -> {
                requireNotNull(serializer) { "No serializer provided for ${message?.let { it::class.qualifiedName } ?: "null"}" }
                val text = application.json.encodeToString(serializer, message)
                response.respondText(text, ContentType.Application.Json, status)
            }
        }
    }

    /** Sends a redirect response to the specified [url]. */
    fun respondRedirect(url: String, permanent: Boolean = false) {
        response.respondRedirect(url, permanent)
    }

    /** Sends raw bytes with the specified content type. */
    fun respondBytes(bytes: ByteArray, contentType: ContentType, status: HttpStatusCode = HttpStatusCode.OK) {
        response.respondBytes(bytes, contentType, status)
    }

    /**
     * Sends a streaming response with coroutine-native backpressure.
     * The [block] receives a [StreamingResponse] whose suspend write methods forward bytes
     * to the client, suspending the coroutine when the network cannot keep up.
     */
    suspend fun respondStreaming(
        contentType: ContentType,
        status: HttpStatusCode = HttpStatusCode.OK,
        block: suspend (StreamingResponse) -> Unit,
    ) {
        response.respondStreaming(contentType, status, block)
    }

    /**
     * Like [respondStreaming], with [timeLimit] in place of the default 5-minute limit; `null` streams
     * until [block] returns (see [ServerResponse.respondStreaming]).
     */
    suspend fun respondStreaming(
        contentType: ContentType,
        status: HttpStatusCode,
        timeLimit: Duration? = null,
        block: suspend (StreamingResponse) -> Unit,
    ) {
        response.respondStreaming(contentType, status, timeLimit, block)
    }
}

/**
 * Interface for custom response content that controls how it is written to the response.
 * Implementations can perform streaming, chunked encoding, or other specialized output.
 */
interface ServerResponseContent {
    /** Writes this content to the given [call] with the specified [status] code. */
    suspend fun writeTo(call: ServerCall, status: HttpStatusCode)
}

/**
 * Manages session state for a single request-response exchange.
 * Sessions are backed by cookies and support get/set operations for session values.
 */
class SessionManager(private val call: ServerCall) {
    @Volatile
    private var _session: Any? = null
    @Volatile
    private var _modified = false

    /**
     * Loads an existing session value without marking the session as modified.
     * Use this when restoring a session from a cookie during authentication so that the
     * response does not redundantly rewrite the cookie.
     */
    @Internal
    fun onLoad(session: Any) {
        _session = session
    }

    /** Sets the session value for this call. The session cookie will be updated in the response. */
    fun set(session: Any) {
        _session = session
        _modified = true
    }

    /** Returns the current session value, or null if no session is active. */
    @Suppress("UNCHECKED_CAST")
    fun <T> get(): T? = _session as? T

    /** Returns true if the session has been modified during this request. */
    val isModified: Boolean get() = _modified

    /** Returns the raw session value for serialization by the session middleware. */
    fun raw(): Any? = _session

    /** Clears the session for this call. */
    fun clear() {
        _session = null
        _modified = true
    }
}
