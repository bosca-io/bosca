package bosca.profiles.web

import kotlinx.serialization.Serializable

@Serializable
data class ThirdPartyProviderRow(
    val name: String,
    val key: String,
    val connected: Boolean,
) {
    val connectUrl: String get() = profilesOAuthUrl(key, "connect", "/security?tab=connected")
}
