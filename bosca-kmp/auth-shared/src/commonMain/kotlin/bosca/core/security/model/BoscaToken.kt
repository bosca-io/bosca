package bosca.core.security.model

import kotlinx.serialization.Serializable

/**
 * JWT access token issued after successful authentication, mirroring the
 * backend `Token` GraphQL type. The validity window is expressed as Unix
 * epoch **seconds** (the schema types these as `Int!`).
 *
 * Port of `BoscaToken` from `@bosca/auth-client-browser` (types.ts).
 */
data class BoscaToken(
    /** Encoded JWT string for use in `Authorization: Bearer` headers. */
    val token: String,
    /** Unix timestamp (seconds) when this token expires. */
    val expiresAt: Int,
    /** Unix timestamp (seconds) when this token was issued. */
    val issuedAt: Int,
)

/**
 * Lightweight metadata persisted alongside the access token so the
 * [bosca.core.security.TokenManager] can schedule a refresh after a cold start
 * without re-parsing the JWT. Serialized to storage as JSON.
 *
 * Port of `TokenMetadata` from types.ts.
 */
@Serializable
data class TokenMetadata(
    /** Unix timestamp (seconds) when the associated access token expires. */
    val expiresAt: Int,
    /** Unix timestamp (seconds) when the associated access token was issued. */
    val issuedAt: Int,
)
