package bosca.analytics.server

import bosca.analytics.model.Device
import bosca.analytics.model.EventContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Builds an [EventContext] for server-originated events (error captures,
 * server-side analytics). Client SDKs populate [EventContext] with real
 * device and session information; for server-side events the device
 * fields describe the JVM host and the session is a per-process id so
 * that [ErrorGroupServiceImpl][bosca.analytics.service.ErrorGroupServiceImpl]
 * can group and count server errors correctly.
 */
object ServerEventContext {

    private val hostname: String = runCatching {
        java.net.InetAddress.getLocalHost().hostName
    }.getOrDefault("unknown")

    @OptIn(ExperimentalUuidApi::class)
    private val processSessionId: String = Uuid.random().toString()

    fun build(
        appId: String,
        sessionId: String? = null,
        userId: String? = null,
        installationId: String? = null,
        device: Device? = null,
    ): EventContext {
        val serverDevice = Device(
            installationId = hostname,
            manufacturer = "jvm",
            model = hostname,
            platform = System.getProperty("os.name") ?: "unknown",
            primaryLocale = "en",
            systemName = "jvm",
            timezone = java.util.TimeZone.getDefault().id,
            type = "server",
            version = System.getProperty("java.version") ?: "unknown",
        )
        val contextDevice = when {
            device != null && installationId != null && device.installationId != installationId ->
                device.copy(installationId = installationId)
            device != null -> device
            installationId != null -> Device(
                installationId = installationId,
                manufacturer = "",
                model = "",
                platform = "",
                primaryLocale = "",
                systemName = "",
                timezone = "",
                type = "unknown",
                version = "",
            )
            else -> serverDevice
        }
        return EventContext(
            appId = appId,
            appVersion = ServerEventContext::class.java.`package`?.implementationVersion ?: "dev",
            device = contextDevice,
            sessionId = sessionId ?: processSessionId,
            userId = userId,
        )
    }
}
