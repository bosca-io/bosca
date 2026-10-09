package bosca.bible.providers.configuration

import bosca.db.migrations.Migration
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers

@Providers
class BibleProvidersConfiguration {

    @Provider(name = "bible-providers-migrations")
    fun migration(): Migration = BibleProvidersMigration()
}
