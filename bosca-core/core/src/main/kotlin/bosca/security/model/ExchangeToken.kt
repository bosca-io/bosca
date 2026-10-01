package bosca.security.model

import bosca.serialization.UUID
import java.time.OffsetDateTime

/**
 * Represents a single-use, short-lived token used to transfer an authenticated session
 * across domains during OAuth2 redirects.
 *
 * When OAuth2 authentication completes on one domain and the user is redirected to a different
 * domain, the session cookie cannot follow. An exchange token is appended to the redirect URL
 * instead, allowing the target domain to call a GraphQL mutation to exchange it for a JWT
 * and refresh token. The token is consumed on first use and expires after 5 minutes.
 */
data class ExchangeToken(
    val principalId: UUID,
    val token: String,
    val created: OffsetDateTime = OffsetDateTime.now(),
    val expires: OffsetDateTime = OffsetDateTime.now().plusMinutes(5)
)
