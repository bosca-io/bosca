package bosca.analytics.persistence.room

import androidx.room3.Room
import bosca.analytics.api.AnalyticsContext
import bosca.analytics.api.AnalyticsElement
import bosca.analytics.api.AnalyticsEvent
import bosca.analytics.api.AnalyticsEventType
import bosca.analytics.api.Browser
import bosca.analytics.api.Device
import bosca.analytics.api.ErrorInfo
import bosca.analytics.api.Geo
import bosca.analytics.api.Page
import java.nio.file.Files

internal fun openTestDatabase(prefix: String): AnalyticsDatabase {
    val path = Files.createTempDirectory(prefix).resolve("analytics.db").toString()
    return buildAnalyticsDatabase(Room.databaseBuilder<AnalyticsDatabase>(name = path))
}

internal fun testContext(sessionId: String, userId: String? = null) = AnalyticsContext(
    appId = "app",
    appVersion = "1",
    clientId = "client",
    device = Device("installation", "Bosca", "test", "DESKTOP", "en", "Test", "UTC", "desktop", "1"),
    geo = Geo("Austin", "TX", "US"),
    browser = Browser("test-agent"),
    sessionId = sessionId,
    userId = userId,
)

internal fun testEvent(clientId: String, created: Long) = AnalyticsEvent(
    clientId = clientId,
    type = AnalyticsEventType.INTERACTION,
    created = created,
    createdMicros = 2,
    element = AnalyticsElement("save", "button"),
    page = Page(path = "/editor"),
    error = ErrorInfo("optional", code = "E1"),
)
