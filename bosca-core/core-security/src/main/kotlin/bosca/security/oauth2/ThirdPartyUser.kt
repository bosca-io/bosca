package bosca.security.oauth2

/**
 * Represents a user profile retrieved from a third-party OAuth2 provider (e.g., Google, Facebook, Apple).
 *
 * Implementations of this interface normalize user profile data from different identity providers
 * into a common structure that can be used during third-party authentication and account linking.
 */
interface ThirdPartyUser {

    /** The unique identifier assigned to this user by the third-party provider. */
    val id: String

    /** The user's full display name as provided by the third-party provider, if available. */
    val name: String?

    /** The user's given (first) name as provided by the third-party provider, if available. */
    val givenName: String?

    /** The user's family (last) name as provided by the third-party provider, if available. */
    val familyName: String?

    /** A URL pointing to the user's profile picture from the third-party provider, if available. */
    val picture: String?

    /** The user's email address as provided by the third-party provider, if available. */
    val email: String?

    /**
     * Whether the third-party provider asserts that [email] has been verified — e.g. the
     * OIDC `email_verified` claim (Google `verified_email` on the legacy userinfo endpoint),
     * or, for providers like Facebook that only ever return an already-verified address, the
     * mere presence of [email].
     *
     * Account-linking decisions MUST require this to be `true` before treating [email] as a
     * trustworthy account key — an unverified provider email is attacker-controllable and
     * must never be used to resolve onto, or take over, an existing account.
     */
    val emailVerified: Boolean
}