package bosca.nats.admin.configuration

import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.server.BoscaApplication
import kotlinx.serialization.Serializable

/**
 * Configuration for connecting to the NATS server's HTTP monitoring interface.
 * The monitoring URL defaults to `http://localhost:8222`, which is the standard
 * NATS monitoring port separate from the NATS protocol port (4222).
 */
@Serializable
data class NatsMonitoringConfig(
    val monitoringUrl: String = "http://localhost:8222",
)

/**
 * Provides the [NatsMonitoringConfig] from the application configuration under `natsMonitoring`.
 */
@Providers
class Configuration {

    @Provider(singleton = true)
    fun natsMonitoringConfig(application: BoscaApplication): NatsMonitoringConfig {
        val config = application.environment.config
        return config.propertyOrNull("natsMonitoring")?.getAs<NatsMonitoringConfig>()
            ?: NatsMonitoringConfig()
    }
}
