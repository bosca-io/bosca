package bosca.security.model

import kotlinx.serialization.Serializable

@Serializable
enum class SignupTokenType {
    ORGANIZATION,
    COMMUNITY_GROUP
}

@Serializable
data class SignupToken(
    val type: SignupTokenType,
    val token: String,
)
