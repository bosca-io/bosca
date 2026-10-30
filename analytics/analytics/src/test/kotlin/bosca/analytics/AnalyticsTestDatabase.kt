package bosca.analytics

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.migrations.CoreMigration
import bosca.db.migrations.FlywayMigration
import bosca.test.resources.SharedPostgreSQLContainer

/** Migrates a class-scoped template once and supplies an independent database for every test. */
internal class AnalyticsTestDatabase {
    private val template = SharedPostgreSQLContainer().withDatabaseName("analytics-template")

    suspend fun start() {
        template.start()
        val pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(
            url = template.jdbcUrl,
            user = template.username,
            password = template.password,
        ), key = "analytics-template"))
        try {
            FlywayMigration(pool).migrate(listOf(CoreMigration()))
        } finally {
            pool.close()
        }
    }

    fun copy(): SharedPostgreSQLContainer = template.copy()

    fun stop() = template.stop()
}
