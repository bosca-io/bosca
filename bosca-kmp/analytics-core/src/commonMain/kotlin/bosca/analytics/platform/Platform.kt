package bosca.analytics.platform

import bosca.analytics.api.Device
import bosca.analytics.persistence.room.AnalyticsDatabase

internal expect fun currentDevice(installationId: String): Device

internal expect fun currentAppVersion(): String

internal expect fun currentMicros(): Int

internal expect fun createAnalyticsDatabase(): AnalyticsDatabase
