package bosca.devices.configuration

import bosca.db.migrations.Migration
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers

@Providers
class Configuration {

    @Provider(name = "devices-migrations")
    fun migration(): Migration = DevicesMigration()
}
