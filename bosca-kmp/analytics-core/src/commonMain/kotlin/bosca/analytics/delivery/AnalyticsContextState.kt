package bosca.analytics.delivery

import bosca.analytics.api.AnalyticsContext
import bosca.analytics.api.Device
import bosca.analytics.api.Geo
import bosca.analytics.platform.currentDevice
import bosca.core.analytics.InstallationIdProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlin.uuid.Uuid

internal data class AnalyticsContextSnapshot(
    val id: String,
    val value: AnalyticsContext,
)

internal class AnalyticsContextState(private val config: BoscaSinkConfig) {
    private val current = MutableStateFlow(
        AnalyticsContextSnapshot(
            id = newId(),
            value = AnalyticsContext(
                appId = config.appId,
                appVersion = config.appVersion,
                clientId = config.clientId,
                device = Device("", "", "", "", "", "", "", "", ""),
                sessionId = newId(),
            ),
        ),
    )

    fun snapshot(): AnalyticsContextSnapshot = current.value

    fun snapshot(userId: String?): AnalyticsContextSnapshot {
        if (config.anonymous) return current.value
        return current.updateAndGet { snapshot ->
            if (snapshot.value.userId == userId) snapshot
            else AnalyticsContextSnapshot(newId(), snapshot.value.copy(userId = userId))
        }
    }

    fun setUserId(userId: String?) {
        if (!config.anonymous && current.value.value.userId != userId) {
            update { it.copy(userId = userId) }
        }
    }

    fun setGeo(geo: Geo) {
        update { it.copy(geo = geo) }
    }

    fun startSession(): String = newId().also { sessionId ->
        update { it.copy(sessionId = sessionId) }
    }

    fun sessionId(): String = current.value.value.sessionId

    suspend fun initialize(installationIdProvider: InstallationIdProvider): AnalyticsContext {
        if (current.value.value.device.installationId.isBlank()) {
            val installationId = installationIdProvider.getOrCreate()
            update { it.copy(device = currentDevice(installationId)) }
        }
        return current.value.value
    }

    private fun update(transform: (AnalyticsContext) -> AnalyticsContext) {
        current.update { snapshot -> AnalyticsContextSnapshot(newId(), transform(snapshot.value)) }
    }

    private companion object {
        fun newId(): String = Uuid.random().toString()
    }
}
