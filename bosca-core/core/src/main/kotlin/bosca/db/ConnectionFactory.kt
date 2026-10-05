package bosca.db

import bosca.server.BoscaApplication
import bosca.server.config.ConfigValue
import kotlinx.serialization.Serializable
import org.flywaydb.core.api.configuration.FluentConfiguration
import java.sql.Connection
import java.sql.DriverManager

@Serializable
data class ConnectionConfig(
    val url: String,
    val user: String,
    val password: String,
    val driverClassName: String? = null,
    val maxConnections: Int? = null
) {

    companion object {

        fun BoscaApplication.get(key: String): ConnectionConfig {
            val config = environment.config.propertyOrNull("database.$key")?.getAs<ConnectionConfig>() ?: error("Database configuration not found: $key")
            return config
        }

        fun BoscaApplication.getOrNull(key: String): ConnectionConfig? {
            val config = environment.config.propertyOrNull("database.$key")?.getAs<ConnectionConfig>() ?: return null
            return config
        }

    }
}

/**
 * Factory for creating JDBC [Connection] instances and configuring Flyway for database migrations.
 *
 * Each implementation corresponds to a named database configuration and controls
 * the connection parameters, driver, and pool sizing.
 */
interface ConnectionFactory {
    /** The configuration key identifying this database (e.g., "primary", "analytics"). */
    val key: String
    /** The maximum number of pooled connections allowed for this database. */
    val maxConnections: Int

    /**
     * Creates a new JDBC [Connection] using the configured URL, user, and password.
     *
     * @return a new database connection
     */
    fun create(): Connection

    /**
     * Configures the given Flyway [FluentConfiguration] with this factory's data source settings
     * so that Flyway migrations can connect to the same database.
     *
     * @param flyway the Flyway configuration builder to populate
     * @return the same [flyway] configuration for chaining
     */
    fun setDataSource(flyway: FluentConfiguration): FluentConfiguration
}

class ConnectionFactoryImpl(
    private val config: ConnectionConfig,
    override val key: String
) : ConnectionFactory {

    init {
        Class.forName(config.driverClassName?.takeIf { it.isNotEmpty() } ?: "org.postgresql.Driver")
    }

    override val maxConnections: Int = config.maxConnections ?: 100

    override fun create(): Connection = DriverManager.getConnection(config.url, config.user, config.password)

    override fun setDataSource(flyway: FluentConfiguration): FluentConfiguration {
        return flyway.dataSource(config.url, config.user, config.password)
    }
}
