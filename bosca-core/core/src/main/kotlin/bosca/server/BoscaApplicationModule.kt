package bosca.server

/**
 * Represents a self-contained server module that configures a specific aspect
 * of the application such as infrastructure services, route registration,
 * security, or middleware installation.
 *
 * Modules are installed onto a [BoscaApplication] during server startup via
 * [BoscaApplication.install]. They may register DI providers, add routes,
 * install middleware, or perform any other initialization needed for their
 * feature area.
 *
 * Implementations that need external dependencies should accept them through
 * constructor parameters rather than pulling them from the DI container
 * at construction time.
 */
interface BoscaApplicationModule {

    /**
     * Installs this module onto the given [application], performing all
     * necessary configuration such as DI provider registration, route
     * definition, and middleware installation.
     */
    suspend fun install(application: BoscaApplication)
}
