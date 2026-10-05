package bosca.configuration

import bosca.db.ConnectionConfig.Companion.get
import bosca.db.ConnectionFactory
import bosca.db.ConnectionFactoryImpl
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.server.BoscaApplication

@Providers
class DatabaseProviders {

    @Provider
    fun factory(application: BoscaApplication): ConnectionFactory {
        val config = application.get("postgres")
        return ConnectionFactoryImpl(config, "postgres")
    }
}
