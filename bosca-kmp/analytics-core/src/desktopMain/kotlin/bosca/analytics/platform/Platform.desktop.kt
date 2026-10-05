package bosca.analytics.platform

import androidx.room3.Room
import bosca.analytics.api.Device
import bosca.analytics.persistence.room.AnalyticsDatabase
import bosca.analytics.persistence.room.buildAnalyticsDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.TimeZone

internal actual fun currentDevice(installationId: String): Device = Device(
    installationId = installationId,
    manufacturer = System.getProperty("java.vendor") ?: "Unknown",
    model = System.getProperty("os.arch") ?: "Unknown",
    platform = "DESKTOP",
    primaryLocale = Locale.getDefault().toLanguageTag(),
    systemName = System.getProperty("os.name") ?: "Desktop",
    timezone = TimeZone.getDefault().id,
    type = "desktop",
    version = System.getProperty("os.version") ?: "Unknown",
)

internal actual fun currentAppVersion(): String =
    System.getProperty(APP_VERSION_PROPERTY)?.takeIf(String::isNotBlank) ?: "dev"

internal actual fun currentMicros(): Int = ((System.nanoTime() / 1_000L) % 1_000L).toInt()

internal actual fun createAnalyticsDatabase(): AnalyticsDatabase {
    val directory = Path.of(System.getProperty("user.home"), ".bosca")
    Files.createDirectories(directory)
    return buildAnalyticsDatabase(
        Room.databaseBuilder<AnalyticsDatabase>(
            name = directory.resolve("bosca-analytics-core.db").toString(),
        ),
    )
}

internal const val APP_VERSION_PROPERTY = "bosca.app.version"
