package bosca.core.security.model

/**
 * Complete authentication response returned after a successful login or token
 * exchange, mirroring the backend `LoginResponse` type.
 *
 * Port of `AuthResponse` from types.ts. [profile] is null when the principal
 * has no profiles; [refreshToken] is null when refresh tokens are disabled.
 */
data class AuthResponse(
    val principal: Principal,
    val profile: List<Profile>?,
    val token: BoscaToken,
    val refreshToken: String?,
)
