package bosca.analytics.platform

import android.os.Build
import androidx.room3.Room
import bosca.analytics.api.Device
import bosca.analytics.persistence.room.AnalyticsDatabase
import bosca.analytics.persistence.room.buildAnalyticsDatabase
import bosca.core.platform.PlatformContexts
import java.util.Locale
import java.util.TimeZone

internal actual fun createAnalyticsDatabase(): AnalyticsDatabase {
    val databaseFile = PlatformContexts.context.applicationContext.getDatabasePath("bosca-analytics-core.db")
    val databaseDirectory = requireNotNull(databaseFile.parentFile)
    check(databaseDirectory.isDirectory || databaseDirectory.mkdirs()) {
        "Failed to create the Bosca analytics database directory"
    }
    return buildAnalyticsDatabase(
        Room.databaseBuilder<AnalyticsDatabase>(name = databaseFile.absolutePath),
    )
}

internal actual fun currentDevice(installationId: String): Device = Device(
    installationId = installationId,
    manufacturer = Build.MANUFACTURER.ifBlank { "Unknown" },
    model = Build.MODEL.ifBlank { "Unknown" },
    platform = "ANDROID",
    primaryLocale = Locale.getDefault().toLanguageTag(),
    systemName = "Android",
    timezone = TimeZone.getDefault().id,
    type = "mobile",
    version = Build.VERSION.RELEASE.ifBlank { Build.VERSION.SDK_INT.toString() },
)

@Suppress("DEPRECATION")
internal actual fun currentAppVersion(): String = runCatching {
    val context = PlatformContexts.context.applicationContext
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
        ?.takeIf(String::isNotBlank)
}.getOrNull() ?: "dev"

internal actual fun currentMicros(): Int = ((System.nanoTime() / 1_000L) % 1_000L).toInt()
