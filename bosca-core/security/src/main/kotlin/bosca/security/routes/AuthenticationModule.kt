package bosca.security.routes

import bosca.db.ConnectionPool
import bosca.di.provide
import bosca.di.provides
import bosca.graphql.GraphQLConnectionInitAuthenticator
import bosca.observability.ErrorCapture
import bosca.security.service.ApiTokenService
import bosca.security.service.AuthenticationProviders
import bosca.security.service.SecurityConfiguration
import bosca.security.service.SecurityService
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule
import bosca.server.middleware.SessionMiddleware
import io.opentelemetry.api.trace.Tracer

/**
 * Installs JWT, HTTP Basic, and session cookie authentication by creating and
 * registering the [BoscaAuthMiddleware], the [SessionMiddleware] for cookie
 * persistence, and optionally registering the authentication provider list
 * in the DI container.
 */
class AuthenticationModule(
    private val includeProviders: Boolean = true,
    private val includeSession: Boolean = true
) : BoscaApplicationModule {

    override suspend fun install(application: BoscaApplication) = with(application) {
        val securityConfiguration = provide<SecurityConfiguration>()
        if (includeProviders) {
            provides {
                val providers = mutableListOf(null, "session", "basic", "api_token")
                securityConfiguration.oauth2.filter { it.enabled }.forEach {
                    providers.add(it.type)
                }
                AuthenticationProviders(providers.toTypedArray())
            }
        }
        val connectionPool = provide<ConnectionPool>()
        val securityService = provide<SecurityService>()
        val apiTokenService = provide<ApiTokenService>()
        val tracer = provide<Tracer>()
        val errorCapture = provide<ErrorCapture>()
        val cookieMaxAge = securityService.getMaxTokenAgeInSeconds()

        val authMiddleware = BoscaAuthMiddleware(
            securityConfiguration = securityConfiguration,
            connectionPool = connectionPool,
            securityService = securityService,
            apiTokenService = apiTokenService,
            tracer = tracer,
            cookieMaxAge = cookieMaxAge,
            errorCapture = errorCapture,
        )
        provides { authMiddleware }
        // Lets GraphQLModule authenticate WebSocket subscriptions from the
        // connection_init payload (browsers can't set an Authorization
        // header on a WebSocket upgrade).
        provides<GraphQLConnectionInitAuthenticator> { ConnectionInitAuthenticator(authMiddleware) }
        installAuth(authMiddleware)

        if (includeSession) {
            install(SessionMiddleware(authMiddleware))
        }
    }
}
