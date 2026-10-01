package bosca.security.model

import kotlinx.serialization.Serializable

@Serializable
data class Token(
    val expiresAt: Int,
    val issuedAt: Int,
    val token: String
)