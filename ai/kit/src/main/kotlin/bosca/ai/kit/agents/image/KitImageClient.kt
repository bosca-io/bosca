package bosca.ai.kit.agents.image

import com.google.auth.oauth2.GoogleCredentials
import com.google.genai.Client

/**
 * Kit's Google Gemini image client + the configured image [model] id. Built once by DI from the
 * `google.genai.account` service-account JSON and `google.genai.image.model` config (see
 * `Configuration.kitImageClient`). The Gemini [Client] is built lazily on first use and only when an
 * account is configured, so a deployment without image credentials still starts — the image tools then
 * fail gracefully via [require] rather than at boot.
 */
class KitImageClient(
    private val account: String?,
    val model: String,
) {

    private val lazyClient: Client? by lazy {
        account?.takeIf { it.isNotBlank() }?.let { json ->
            val credentials = json.toByteArray().inputStream().use { GoogleCredentials.fromStream(it) }
            Client.builder().credentials(credentials).build()
        }
    }

    /** The configured Gemini client, or throws a clear error when image generation isn't configured. */
    fun require(): Client = lazyClient
        ?: error("Image generation is not configured (set the 'google.genai.account' service-account config).")
}
