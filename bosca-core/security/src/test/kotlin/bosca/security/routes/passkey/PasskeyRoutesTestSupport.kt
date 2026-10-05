package bosca.security.routes.passkey

import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.PasskeyCredentialAttributes
import bosca.security.model.Principal
import bosca.security.model.PrincipalCredential
import bosca.security.routes.security.SecurityRouteTestCall
import bosca.serialization.UUID

internal fun SecurityRouteTestCall.authenticate(
    principal: Principal = Principal(id = UUID.random(), anonymous = false, verified = true),
): Principal {
    authenticationContext.principal(
        "test",
        AuthenticatedPrincipal(principal, emptyList()),
    )
    return principal
}

internal fun passkeyCredential(
    principalId: UUID,
    identifier: String = "credential-id",
    name: String = "Laptop",
    publicKeyCose: String = "AQ",
    signCount: Long = 0,
) = PrincipalCredential(
    principal = principalId,
    attributes = PasskeyCredentialAttributes(
        identifier = identifier,
        name = name,
        publicKeyCose = publicKeyCose,
        signCount = signCount,
        aaguid = null,
        transports = listOf("internal"),
        lastUsedAt = "2026-07-24T00:00:00Z",
        createdAt = "2026-07-23T00:00:00Z",
    ),
)
