package bosca.postgres.admin.configuration

import bosca.db.ConnectionConfig.Companion.get
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.postgres.admin.pgbouncer.PgBouncerClient
import bosca.server.BoscaApplication

@Providers
class PostgresAdminProviders {

    @Provider
    fun pgBouncerClient(application: BoscaApplication): PgBouncerClient {
        val config = application.get("postgres")
        return PgBouncerClient(config)
    }
}
