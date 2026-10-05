@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package bosca.analytics.platform

import androidx.room3.Room
import bosca.analytics.api.Device
import bosca.analytics.persistence.room.AnalyticsDatabase
import bosca.analytics.persistence.room.buildAnalyticsDatabase
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.NSUserDomainMask
import platform.Foundation.abbreviation
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIDevice

internal actual fun currentDevice(installationId: String): Device {
    val device = UIDevice.currentDevice
    return Device(
        installationId = installationId,
        manufacturer = "Apple",
        model = device.model,
        platform = "IOS",
        primaryLocale = NSLocale.currentLocale().localeIdentifier(),
        systemName = device.systemName,
        timezone = NSTimeZone.localTimeZone().abbreviation() ?: "",
        type = "mobile",
        version = device.systemVersion,
    )
}

internal actual fun currentAppVersion(): String =
    (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String)
        ?.takeIf(String::isNotBlank)
        ?: "dev"

internal actual fun currentMicros(): Int =
    ((NSDate().timeIntervalSince1970() * 1_000_000.0).toLong() % 1_000L).toInt()

internal actual fun createAnalyticsDatabase(): AnalyticsDatabase {
    val directory = requireNotNull(
        NSFileManager.defaultManager.URLForDirectory(
            directory = NSApplicationSupportDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        ),
    )
    val path = requireNotNull(directory.URLByAppendingPathComponent("bosca-analytics-core.db")?.path)
    return buildAnalyticsDatabase(Room.databaseBuilder<AnalyticsDatabase>(name = path))
}
