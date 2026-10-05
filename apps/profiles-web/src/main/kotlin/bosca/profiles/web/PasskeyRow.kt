package bosca.profiles.web

import kotlinx.serialization.Serializable

@Serializable
data class PasskeyRow(
    val credentialId: String,
    val name: String,
    val created: String,
    val lastUsed: String,
    val transports: String,
)
