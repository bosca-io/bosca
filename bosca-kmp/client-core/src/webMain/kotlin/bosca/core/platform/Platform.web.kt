package bosca.core.platform

import bosca.core.notifications.PushDevicePlatform
import bosca.core.notifications.PushTokenProvider
import bosca.core.notifications.UnsupportedPushTokenProvider
import bosca.core.preferences.Preferences
import bosca.core.preferences.WebPreferences
import bosca.core.security.IdentityStorage
import bosca.core.security.TokenStorage
import bosca.core.security.WebTokenStorage
import bosca.di.provides

actual fun registerPlatformDependencies() {
    val authStorage = WebTokenStorage()
    provides<TokenStorage> { authStorage }
    provides<IdentityStorage> { authStorage }
    provides<Preferences> {
        WebPreferences()
    }
    provides<PushTokenProvider> {
        UnsupportedPushTokenProvider(PushDevicePlatform.WEB)
    }
}

internal actual fun currentAppVersion(): String = "dev"
