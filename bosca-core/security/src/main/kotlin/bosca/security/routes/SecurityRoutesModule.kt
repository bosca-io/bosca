package bosca.security.routes

import bosca.routes.configureSecurityRoutes
import bosca.security.routes.oauth2.OAuth2Module
import bosca.security.routes.passkey.PasskeyModule
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule

/**
 * Orchestrator module that installs all security-related sub-modules including
 * authentication middleware, OAuth2 provider integration, WebAuthn passkey support,
 * and KSP-generated route controllers for login, signup, verification, and password recovery.
 */
class SecurityRoutesModule : BoscaApplicationModule {
    override suspend fun install(application: BoscaApplication) {
        application.install(AuthenticationModule())
        application.install(OAuth2Module())
        application.install(PasskeyModule())
        application.configureSecurityRoutes()
    }
}
