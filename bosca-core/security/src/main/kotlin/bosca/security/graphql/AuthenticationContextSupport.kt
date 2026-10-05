package bosca.security.graphql

import bosca.security.model.AuthenticatedPrincipal
import bosca.security.service.AuthenticationContext
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityException
import bosca.serialization.UUID

internal fun AuthenticationContext.requirePrincipal(): AuthenticatedPrincipal =
    principal() ?: throw SecurityException("Unauthorized access")

internal fun AuthenticationContext.requirePrincipalId(): UUID = requirePrincipal().id

/** Requires account authentication that does not originate from an API token. */
internal fun AuthenticationContext.requireInteractivePrincipal(): AuthenticatedPrincipal {
    val principal = requirePrincipal()
    if (principal is ScopedAuthenticatedPrincipal) {
        throw SecurityException("API tokens cannot manage authentication credentials")
    }
    return principal
}

internal fun AuthenticationContext?.authenticatedPrincipalOrNull(): AuthenticatedPrincipal? =
    this?.principal()
