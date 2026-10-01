package bosca.core.platform

import bosca.core.notifications.IosPushTokenProvider
import bosca.core.notifications.PushTokenProvider
import bosca.core.preferences.IOSPreferences
import bosca.core.preferences.Preferences
import bosca.core.preferences.auth.iOSTokenStorage
import bosca.core.security.IdentityStorage
import bosca.core.security.TokenStorage
import bosca.di.provides
import platform.Foundation.NSBundle

actual fun registerPlatformDependencies() {
    val authStorage = iOSTokenStorage()
    provides<TokenStorage> { authStorage }
    provides<IdentityStorage> { authStorage }
    provides<Preferences> {
        IOSPreferences()
    }
    provides<PushTokenProvider> {
        IosPushTokenProvider()
    }
}

actual val CurrentPlatformType: PlatformType
    get() = PlatformType.Ios

internal actual fun currentAppVersion(): String =
    (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String)
        ?.takeIf(String::isNotBlank)
        ?: "dev"
