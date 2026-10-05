package bosca.profiles.web

import kotlinx.serialization.Serializable

@Serializable
data class CredentialRow(
    val identifier: String,
    val type: String,
    val provider: String,
    val originator: String,
    val lastOriginator: String,
) {
    val oauth: Boolean get() = type == "OAUTH2"
    val label: String get() = provider.ifBlank { type.lowercase().replaceFirstChar(Char::uppercase) }
}
