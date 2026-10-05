package bosca.service

/**
 * Base interface for service implementations in the Bosca framework.
 *
 * Classes annotated with [@ServiceImplementation][bosca.service.annotation.ServiceImplementation]
 * that implement this interface are automatically discovered and registered
 * into the DI system by the KSP-generated code.
 *
 * Services that hold background resources (coroutine jobs, channels,
 * connection pools) should override [shutdown] to drain or close them
 * gracefully. The server calls [shutdown] on every registered service
 * during application stop, before cancelling the application scope.
 */
interface Service {

    /**
     * Gracefully releases any resources held by this service. Called
     * once during server shutdown, before the application coroutine
     * scope is cancelled. The default implementation is a no-op.
     *
     * Implementations should drain in-flight work (e.g. buffered
     * channel items) and cancel background jobs so that no work is
     * silently lost on a clean stop. The method is suspending so
     * implementations can await completion of pending writes.
     */
    suspend fun shutdown() {}
}
