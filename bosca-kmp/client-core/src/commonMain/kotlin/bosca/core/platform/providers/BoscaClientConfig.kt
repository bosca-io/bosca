package bosca.core.platform.providers

/** Application identity attached to outgoing Bosca client requests. */
data class BoscaClientConfig(
    val appId: String,
    val appVersion: String,
) {
    init {
        require(appId.isNotBlank()) { "App ID must not be blank" }
        require(appVersion.isNotBlank()) { "App version must not be blank" }
    }
}
