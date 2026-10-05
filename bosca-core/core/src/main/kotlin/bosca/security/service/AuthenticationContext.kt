package bosca.security.service

import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Group
import bosca.security.model.Principal
import bosca.server.auth.CallAuthenticationContext

open class AuthenticationContext(
    private val context: CallAuthenticationContext?,
    private val providers: AuthenticationProviders?
) {

    open fun principal(): AuthenticatedPrincipal? {
        if (context == null || providers == null) return null
        for (provider in providers.providers) {
            val principal = context.principal(provider)
            if (principal != null) {
                return principal
            }
        }
        return context.anyPrincipal()
    }
}

class ImpersonatedAuthenticationContext(
    principal: Principal,
    groups: List<Group>
) : AuthenticationContext(null, null) {

    private val authenticatedPrincipal = AuthenticatedPrincipal(principal, groups)

    override fun principal(): AuthenticatedPrincipal = authenticatedPrincipal
}

class AuthenticationProviders(val providers: Array<String?>)
