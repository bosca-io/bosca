package bosca.core.preferences.providers

import bosca.core.preferences.Preferences
import bosca.core.preferences.PreferencesRepository
import bosca.core.preferences.impl.PreferencesRepositoryImpl
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers

@Providers
class RepositoryProviders {

    @Provider(singleton = true)
    fun preferencesRepository(preferences: Preferences): PreferencesRepository = PreferencesRepositoryImpl(preferences)
}
