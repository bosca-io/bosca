package bosca.security.oauth2

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DefaultOauth2User(
    override val id: String,
    override val name: String? = null,
    @SerialName("given_name")
    override val givenName: String? = null,
    @SerialName("family_name")
    override val familyName: String? = null,
    override val picture: String? = null,
    override val email: String? = null,
    /** OIDC `email_verified` claim (Apple, Google OIDC `userinfo`, generic OIDC). */
    @SerialName("email_verified")
    @Serializable(with = LenientBooleanSerializer::class)
    val emailVerifiedClaim: Boolean = false,
    /** Google's legacy `/oauth2/v2/userinfo` spells the same flag `verified_email`. */
    @SerialName("verified_email")
    @Serializable(with = LenientBooleanSerializer::class)
    val verifiedEmailClaim: Boolean = false,
) : ThirdPartyUser {

    override val emailVerified: Boolean
        get() = emailVerifiedClaim || verifiedEmailClaim
}