package bosca.security.routes.security

import bosca.security.model.SignupToken
import bosca.security.model.SignupTokenType
import bosca.server.ServerCall
import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
data class LoginRequest(
    val identifier: String,
    val password: String
)

@Serializable
data class ForgotPasswordRequest(
    val identifier: String
)

@Serializable
data class ResetPasswordRequest(
    val password: String,
    val token: String
)

@Serializable
data class SignupRequest(
    val identifier: String,
    val password: String,
    val profile: bosca.profile.profile.model.ProfileInput,
    val organization: bosca.profile.organization.model.OrganizationInput?,
    val tokens: List<SignupToken> = emptyList()
)

@Serializable
data class ErrorResponse(
    val message: String?
)

/**
 * Validates a redirect URL against the allowed redirects list by comparing
 * the origin (scheme + host + port) to prevent open redirect attacks.
 * Returns the redirect if valid, "/" if not, or null if the input is null.
 *
 * When [allowedRedirects] is empty, all redirects are rejected and "/" is
 * returned. This is the safe default — callers must explicitly configure
 * allowed origins to enable redirects.
 */
fun validateRedirect(redirect: String?, allowedRedirects: List<String>): String? {
    if (redirect == null) return null
    return try {
        val redirectUri = URI(redirect)
        if (redirectUri.host == null) return "/"
        val redirectOrigin = buildOrigin(redirectUri)
        if (allowedRedirects.any { allowed ->
                try {
                    val allowedUri = URI(allowed)
                    allowedUri.host != null && buildOrigin(allowedUri) == redirectOrigin
                } catch (_: Exception) {
                    false
                }
            }
        ) redirect else "/"
    } catch (_: Exception) {
        "/"
    }
}

private fun buildOrigin(uri: URI): String {
    val scheme = uri.scheme ?: "https"
    val port = if (uri.port != -1) ":${uri.port}" else ""
    return "$scheme://${uri.host}$port"
}

/**
 * Validates a redirect URL, only returning a redirect for form-encoded requests.
 * JSON API requests never redirect — they receive JSON responses instead.
 */
fun getFormRedirect(call: ServerCall, redirect: String?, queryKey: String, allowedRedirects: List<String>): String? {
    if (!call.isFormRequest()) return null
    val effectiveRedirect = redirect ?: call.request.queryParameters[queryKey]
    return validateRedirect(effectiveRedirect, allowedRedirects)
}

/**
 * Extracts the error redirect URL from a query parameter or form body parameter.
 * Only returns a redirect for form-encoded requests. The Referer header is not
 * used because it is client-controlled and could enable open redirect attacks.
 */
fun getErrorRedirect(call: ServerCall, queryKey: String, allowedRedirects: List<String>): String? {
    return getFormRedirect(call, null, queryKey, allowedRedirects)
}

/** Valid password length range enforced at signup and password reset. */
val PASSWORD_LENGTH_RANGE = 8..128

private val EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

/** Validates that the given string is a plausible email address format. */
fun requireValidEmail(email: String) {
    require(email.length <= 254 && EMAIL_PATTERN.matches(email)) { "Invalid email address format" }
}

/** Returns true if the request uses form-urlencoded content type. */
fun ServerCall.isFormRequest(): Boolean =
    request.contentType()?.match("application/x-www-form-urlencoded") == true

internal fun Throwable.isNotVerifiedError(): Boolean =
    message?.contains("not verified") == true

/** Extracts signup tokens from the request's query parameters. */
fun ServerCall.getSignUpTokens(): List<SignupToken> {
    val tokens = mutableListOf<SignupToken>()
    val organization = request.queryParameters["organization"]
    if (!organization.isNullOrBlank()) {
        tokens.add(SignupToken(SignupTokenType.ORGANIZATION, organization))
    }
    val community = request.queryParameters["community"]
    if (!community.isNullOrBlank()) {
        tokens.add(SignupToken(SignupTokenType.COMMUNITY_GROUP, community))
    }
    return tokens
}
