package bosca.core.platform

import bosca.core.notifications.AndroidPushTokenProvider
import bosca.core.notifications.PushTokenProvider
import bosca.core.preferences.AndroidPreferences
import bosca.core.preferences.Preferences
import bosca.core.preferences.auth.AndroidTokenStorage
import bosca.core.security.IdentityStorage
import bosca.core.security.TokenStorage
import bosca.di.provides

actual fun registerPlatformDependencies() {
    val authStorage = AndroidTokenStorage(PlatformContexts.context)
    provides<TokenStorage> { authStorage }
    provides<IdentityStorage> { authStorage }
    provides<Preferences> {
        AndroidPreferences(PlatformContexts.context)
    }
    provides<PushTokenProvider> {
        AndroidPushTokenProvider(PlatformContexts.context)
    }
}
