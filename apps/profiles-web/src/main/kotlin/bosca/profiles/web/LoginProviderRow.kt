package bosca.profiles.web

import kotlinx.serialization.Serializable

@Serializable
data class LoginProviderRow(
    val name: String,
    val loginUrl: String,
)
