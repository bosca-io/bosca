package bosca.security.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class LoginResponse(
    @Contextual
    val principalId: UUID,
    val refreshToken: String?,
    val token: Token,
    /**
     * True when this sign-in *created* the account (the principal did not exist beforehand), as opposed
     * to authenticating an existing one. Defaults to false so every login path that authenticates an
     * existing principal — password, refresh, passkey, exchange-token — reports it correctly without
     * change; only the first-time third-party (OAuth) signup flips it to true.
     */
    val accountCreated: Boolean = false,
    /**
     * The caller-supplied originator of the login request (e.g. an app/client identifier), echoed straight
     * back so the caller can correlate the response with the request that produced it. Null when the
     * caller did not provide one. Carried for email (password) and OAuth2 sign-in.
     */
    val originator: String? = null
)