package bosca.core.platform

actual val CurrentPlatformType: PlatformType
    get() = PlatformType.Desktop

internal actual fun currentAppVersion(): String =
    System.getProperty("bosca.app.version")?.takeIf(String::isNotBlank) ?: "dev"
