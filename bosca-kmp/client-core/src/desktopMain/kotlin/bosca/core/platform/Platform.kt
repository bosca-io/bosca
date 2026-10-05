package bosca.core.platform

import bosca.core.notifications.PushDevicePlatform
import bosca.core.notifications.PushTokenProvider
import bosca.core.notifications.UnsupportedPushTokenProvider
import bosca.core.preferences.DesktopPreferences
import bosca.core.preferences.Preferences
import bosca.core.security.DesktopTokenStorage
import bosca.core.security.IdentityStorage
import bosca.core.security.TokenStorage
import bosca.di.provides

actual fun registerPlatformDependencies() {
    val authStorage = DesktopTokenStorage()
    provides<TokenStorage> { authStorage }
    provides<IdentityStorage> { authStorage }
    provides<Preferences> { DesktopPreferences() }
    provides<PushTokenProvider> { UnsupportedPushTokenProvider(PushDevicePlatform.DESKTOP) }
}
