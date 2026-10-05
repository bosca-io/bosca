package bosca.server.middleware

import bosca.server.HttpStatusCode
import bosca.server.ServerCall

/**
 * CallMiddleware that provides custom error handling for exceptions thrown during request processing
 * and for specific HTTP status codes returned by route handlers.
 *
 * Replaces Ktor's StatusPages plugin with a middleware-based approach.
 */
class StatusPagesMiddleware(
    exceptionHandlers: Map<Class<out Throwable>, suspend (ServerCall, Throwable) -> Unit> = emptyMap(),
    private val statusHandlers: Map<Int, suspend (ServerCall) -> Unit> = emptyMap(),
    private val defaultExceptionHandler: (suspend (ServerCall, Throwable) -> Unit)? = null
) : CallMiddleware {

    /** Exception handlers sorted by class hierarchy depth (most-derived first) so that specific types match before supertypes. */
    private val sortedExceptionHandlers: List<Pair<Class<out Throwable>, suspend (ServerCall, Throwable) -> Unit>> =
        exceptionHandlers.entries
            .sortedByDescending { hierarchyDepth(it.key) }
            .map { it.key to it.value }

    override suspend fun onException(call: ServerCall, cause: Throwable) {
        if (call.response.isCommitted) return

        // Try specific exception handler — sorted by specificity so most-derived types match first
        val handler = sortedExceptionHandlers.firstOrNull { it.first.isInstance(cause) }?.second
        if (handler != null) {
            handler(call, cause)
            return
        }

        // Try default exception handler
        defaultExceptionHandler?.invoke(call, cause)
    }

    override suspend fun afterCall(call: ServerCall) {
        if (call.response.isCommitted) return
        val status = call.response.status() ?: return
        val handler = statusHandlers[status.value] ?: return
        handler(call)
    }

    /** Builder DSL for configuring status page handlers. */
    class Builder {
        @PublishedApi internal val exceptionHandlers = mutableMapOf<Class<out Throwable>, suspend (ServerCall, Throwable) -> Unit>()
        private val statusHandlers = mutableMapOf<Int, suspend (ServerCall) -> Unit>()
        private var defaultExceptionHandler: (suspend (ServerCall, Throwable) -> Unit)? = null

        /** Registers a handler for exceptions of the given type. */
        inline fun <reified T : Throwable> exception(noinline handler: suspend (ServerCall, T) -> Unit) {
            @Suppress("UNCHECKED_CAST")
            exceptionHandlers[T::class.java] = handler as suspend (ServerCall, Throwable) -> Unit
        }

        /** Registers a handler for responses with the given status code. */
        fun status(statusCode: HttpStatusCode, handler: suspend (ServerCall) -> Unit) {
            statusHandlers[statusCode.value] = handler
        }

        /** Registers a default handler for all unhandled exceptions. */
        fun defaultException(handler: suspend (ServerCall, Throwable) -> Unit) {
            defaultExceptionHandler = handler
        }

        /** Builds the [StatusPagesMiddleware] from this configuration. */
        fun build(): StatusPagesMiddleware = StatusPagesMiddleware(exceptionHandlers, statusHandlers, defaultExceptionHandler)
    }

    companion object {
        /** Counts the number of superclasses between [cls] and [Throwable] to determine hierarchy depth. */
        private fun hierarchyDepth(cls: Class<*>): Int {
            var depth = 0
            var current: Class<*>? = cls
            while (current != null) {
                depth++
                current = current.superclass
            }
            return depth
        }
    }
}
