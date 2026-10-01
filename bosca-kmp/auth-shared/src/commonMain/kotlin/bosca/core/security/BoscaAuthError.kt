package bosca.core.security

/**
 * Base type for all authentication failures. Carries a machine-readable [code]
 * alongside the human-readable message. Port of the `errors.ts` hierarchy.
 *
 * These are plain [Exception]s — never [kotlinx.coroutines.CancellationException]
 * — so coroutine cancellation is never masked by auth error handling.
 */
open class BoscaAuthError(message: String, val code: String) : Exception(message)

/** Login credentials were rejected because they match no known account. */
class InvalidCredentialsError(message: String = "Invalid email or password") :
    BoscaAuthError(message, CODE) {
    companion object { const val CODE = "auth/invalid-credentials" }
}

/** A login was attempted for a principal whose account-level verification is not yet complete. */
class PrincipalNotVerifiedError(message: String = "Account has not been verified") :
    BoscaAuthError(message, CODE) {
    companion object { const val CODE = "auth/principal-not-verified" }
}

/** An email address specifically has not been verified, distinct from the [PrincipalNotVerifiedError] gate. */
class EmailNotVerifiedError(message: String = "Email address has not been verified") :
    BoscaAuthError(message, CODE) {
    companion object { const val CODE = "auth/email-not-verified" }
}

/** An auth-required method was called with no valid local session; no request is made. */
open class UnauthenticatedError(message: String = "Not authenticated") :
    BoscaAuthError(message, CODE) {
    companion object { const val CODE = "auth/unauthenticated" }
}

/** The backend explicitly rejected an authenticated request with HTTP 401/403. */
class AuthenticationRejectedError(
    val statusCode: Int,
    message: String = "Authentication rejected with HTTP $statusCode",
) : UnauthenticatedError(message)

/** An access or refresh token has expired and can no longer authenticate. */
class TokenExpiredError(message: String = "Authentication token has expired") :
    BoscaAuthError(message, CODE) {
    companion object { const val CODE = "auth/token-expired" }
}

/** A network request to the backend failed (connectivity / unreachable server). */
class NetworkError(message: String = "Network request failed") :
    BoscaAuthError(message, CODE) {
    companion object { const val CODE = "auth/network-error" }
}

/** An OAuth redirect flow failed or returned an invalid exchange token. */
class OAuthError(message: String = "OAuth authentication failed") :
    BoscaAuthError(message, CODE) {
    companion object { const val CODE = "auth/oauth-error" }
}

/**
 * The GraphQL endpoint returned errors in its response. [errors] holds the
 * server-reported error messages. Named `GraphQLAuthError` to avoid clashing
 * with Apollo's `com.apollographql.apollo.api.Error`.
 */
class GraphQLAuthError(message: String, val errors: List<String> = emptyList()) :
    BoscaAuthError(message, CODE) {
    companion object { const val CODE = "auth/graphql-error" }
}
