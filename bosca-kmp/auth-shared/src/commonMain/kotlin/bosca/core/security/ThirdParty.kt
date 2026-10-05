package bosca.core.security

import bosca.core.security.type.ThirdPartyType

/** Third-party identity providers supported by the native (mobile) sign-in flow. */
enum class ThirdPartyProvider { GOOGLE, FACEBOOK, APPLE }

/** The wire-enum [ThirdPartyType] corresponding to this provider. */
fun ThirdPartyProvider.toThirdPartyType(): ThirdPartyType = when (this) {
    ThirdPartyProvider.GOOGLE -> ThirdPartyType.GOOGLE
    ThirdPartyProvider.FACEBOOK -> ThirdPartyType.FACEBOOK
    ThirdPartyProvider.APPLE -> ThirdPartyType.APPLE
}

/**
 * A user identity returned by a platform [bosca.core.security.providers.ThirdPartyAuthenticationProvider]
 * after a native OAuth sign-in. [token] is the provider-issued token exchanged
 * with the backend via `BoscaAuth.signInWithThirdParty`.
 */
data class ThirdPartyUser(
    val type: ThirdPartyType,
    val id: String,
    val name: String,
    val email: String,
    val picture: String?,
    val token: String? = null,
)
