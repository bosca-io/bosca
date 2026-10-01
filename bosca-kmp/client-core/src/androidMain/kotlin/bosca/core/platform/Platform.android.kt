package bosca.core.platform

actual val CurrentPlatformType: PlatformType
    get() = PlatformType.Android

@Suppress("DEPRECATION")
internal actual fun currentAppVersion(): String = runCatching {
    val context = PlatformContexts.context.applicationContext
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
        ?.takeIf(String::isNotBlank)
}.getOrNull() ?: "dev"
