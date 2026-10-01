package bosca.core.platform

enum class PlatformType { Desktop, WebJs, WebWasm, Android, Ios }

expect fun registerPlatformDependencies()

internal expect fun currentAppVersion(): String

expect val CurrentPlatformType: PlatformType
