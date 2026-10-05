package bosca.db.migrations

import bosca.db.ConnectionPool
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers

@Providers
class Configuration {

    @Provider(singleton = true)
    fun migrations(connectionPool: ConnectionPool): Migrations = FlywayMigration(connectionPool)

    @Provider(name = "core-migrations")
    fun migration(): Migration = CoreMigration()
}