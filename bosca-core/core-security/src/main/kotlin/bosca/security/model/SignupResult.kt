package bosca.security.model

import kotlinx.serialization.Serializable

/**
 * The outcome of a sign-up attempt. Exactly one field is populated:
 * - [principal]: a password sign-up succeeded (the new, typically-unverified account).
 * - [loginResponse]: a third-party sign-up succeeded (account + tokens).
 * - [linkChallenge]: the email already belongs to an existing verified account — the caller must
 *   drive the proof/linking challenge rather than create a duplicate.
 */
@Serializable
data class SignupResult(
    val principal: Principal? = null,
    val loginResponse: LoginResponse? = null,
    val linkChallenge: LinkChallenge? = null,
)
