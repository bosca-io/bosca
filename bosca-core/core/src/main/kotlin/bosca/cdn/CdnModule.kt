package bosca.cdn

import bosca.di.provides
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule

/**
 * Registers a [CdnManager] DI provider based on the application's `cdn.type`
 * configuration, selecting either Cloudflare or a no-op implementation.
 */
class CdnModule : BoscaApplicationModule {
    override suspend fun install(application: BoscaApplication) = with(application) {
        val config = environment.config
        val type = config.propertyOrNull("cdn.type")?.getString() ?: "noop"

        provides<CdnManager>(true) {
            when (type.lowercase()) {
                "cloudflare" -> {
                    val apiToken = config.property("cdn.cloudflare.token").getString()
                    val zoneId = config.property("cdn.cloudflare.zoneId").getString()
                    CloudflareCdnManager(apiToken, zoneId)
                }
                else -> NoOpCdnManager()
            }
        }
    }
}
