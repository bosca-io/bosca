package bosca.bible.providers.configuration

import bosca.bible.providers.apibible.ApiBibleProvider
import bosca.bible.providers.service.BibleProvider
import bosca.configuration.service.ConfigurationService
import bosca.db.migrations.Migration
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import kotlinx.serialization.json.Json

@Providers
class BibleProvidersConfiguration {

    @Provider(name = "bible-providers-migrations")
    fun migration(): Migration = BibleProvidersMigration()

    @Provider(singleton = true, name = "API_BIBLE")
    fun apiBible(configurations: ConfigurationService, json: Json): BibleProvider = ApiBibleProvider(configurations, json)
}
