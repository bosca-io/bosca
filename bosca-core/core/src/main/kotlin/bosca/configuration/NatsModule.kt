package bosca.configuration

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.nats.NatsConnectionPool
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class NatsConnectionConfig(
    val url: String,
    val token: String? = null,
    /** Number of shared physical connections used to distribute client-facing pub/sub handles. */
    val maxConnections: Int? = null,
    val username: String? = null,
    val password: String? = null,
)

/**
 * Registers the NATS connection pool from the application's `nats` configuration block,
 * enabling message queue and pub/sub infrastructure for the server.
 */
class NatsModule : BoscaApplicationModule {
    private val log = LoggerFactory.getLogger(NatsModule::class.java)

    @OptIn(InternalDI::class)
    override suspend fun install(application: BoscaApplication) = with(application) {
        val nats = environment.config.propertyOrNull("nats")?.getAs<NatsConnectionConfig>()
        if (nats != null) {
            val maxConnections = nats.maxConnections ?: NatsConnectionPool.DEFAULT_MAX_CONNECTIONS
            val username = nats.username?.takeIf(String::isNotBlank)
            val password = nats.password?.takeIf(String::isNotBlank)
            // Account credentials take precedence over the token supplied by legacy application files.
            if (username != null && password != null) {
                NatsConnectionPool.register(nats.url, username, password, maxConnections)
            } else {
                if (username != null || password != null) {
                    log.error("nats.username and nats.password must both be set; authenticating with nats.token instead")
                }
                if (nats.token == null) {
                    log.error("nats.token is not configured; connecting to NATS without credentials")
                }
                NatsConnectionPool.register(nats.url, nats.token.orEmpty(), maxConnections)
            }
            onShutdown {
                for (provider in ProviderRegistry.findAll(NatsConnectionPool::class)) {
                    runCatching {
                        provider.get().close()
                    }
                }
            }
        }
    }
}
