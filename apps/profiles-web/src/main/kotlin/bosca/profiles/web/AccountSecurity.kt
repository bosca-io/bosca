package bosca.profiles.web

import bosca.bml.graphql.execute
import bosca.bml.render.client
import bosca.bml.render.currentRenderContext
import bosca.profiles.web.graphql.AccountSecurity

suspend fun securityModel(): SecurityModel {
    val data = client().execute(AccountSecurity, Unit).security
    val principal = data.principals.current
    return SecurityModel(
        principalId = principal.id,
        verified = principal.verified,
        created = principal.created,
        lastLogin = principal.lastLogin ?: "No login recorded",
        credentials = principal.credentials.map {
            CredentialRow(
                it.identifier,
                it.type.toString(),
                it.provider.orEmpty(),
                it.originator.orEmpty(),
                it.lastOriginator.orEmpty(),
            )
        },
        thirdPartyProviders = data.thirdPartyProviders.map { provider ->
            val name = provider.toString()
            ThirdPartyProviderRow(
                name = name.lowercase().replaceFirstChar(Char::uppercase),
                key = name.lowercase(),
                connected = principal.credentials.any {
                    it.type.toString() == "OAUTH2" && it.provider?.equals(name, ignoreCase = true) == true
                },
            )
        },
        passkeys = data.passkeys.current.map {
            PasskeyRow(it.credentialId, it.name, it.createdAt, it.lastUsedAt.orEmpty(), it.transports.joinToString(", "))
        },
        logins = principal.loginHistory.map {
            LoginRow(it.id, it.method, it.created, it.revokedAt.orEmpty(), it.current)
        },
        tab = currentRenderContext().query["tab"].takeIf {
            it == "connected" || it == "passkeys" || it == "logins"
        } ?: "password",
    )
}
