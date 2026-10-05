package bosca.profiles.web

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.profiles.web.graphql.DeliveryChannel
import bosca.profiles.web.graphql.UpdateCommunicationPreference
import bosca.profiles.web.graphql.UpdateQuietHours
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val communicationLog = LoggerFactory.getLogger("bosca.profiles.web.CommunicationsModel")

@Serializable
class CommunicationsModel(
    var rows: List<NotificationRow>,
    var timeZone: String,
    var start: String,
    var end: String,
    var message: String? = null,
    var failed: Boolean = false,
    var messageId: Long = 0,
) {
    val statusClass: String get() = if (failed) "status error" else "status ok"

    suspend fun toggleEmail(key: String, ctx: RenderContext) = toggle(key, DeliveryChannel.EMAIL, ctx)
    suspend fun togglePush(key: String, ctx: RenderContext) = toggle(key, DeliveryChannel.PUSH, ctx)

    private suspend fun toggle(key: String, channel: DeliveryChannel, ctx: RenderContext) {
        val row = rows.firstOrNull { it.key == key } ?: return
        if (!row.optional) return
        val email = channel == DeliveryChannel.EMAIL
        val next = if (email) !row.emailOptedOut else !row.pushOptedOut
        try {
            ctx.gql.execute(
                UpdateCommunicationPreference,
                UpdateCommunicationPreference.Variables(channel, key, next),
            )
            rows = rows.map {
                when {
                    it.key != key -> it
                    email -> it.copy(emailOptedOut = next)
                    else -> it.copy(pushOptedOut = next)
                }
            }
            message = "Communication preference saved."
            failed = false
            messageId += 1
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            communicationLog.warn("Communication preference update failed for {} {}: {}", key, channel, e.toString())
            message = "We couldn't save that preference."
            failed = true
            messageId += 1
        }
    }

    suspend fun saveQuietHours(timeZone: String, start: String, end: String, ctx: RenderContext) {
        val allBlank = timeZone.isBlank() && start.isBlank() && end.isBlank()
        try {
            val result = ctx.gql.execute(
                UpdateQuietHours,
                UpdateQuietHours.Variables(
                    timeZone = if (allBlank) null else timeZone.trim(),
                    start = if (allBlank) null else start.trim(),
                    end = if (allBlank) null else end.trim(),
                ),
            ).communications.updateMyQuietHours
            this.timeZone = result.timeZone.orEmpty()
            this.start = result.dndStartLocal.orEmpty()
            this.end = result.dndEndLocal.orEmpty()
            message = if (allBlank) "Quiet hours cleared." else "Quiet hours saved."
            failed = false
            messageId += 1
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            communicationLog.warn("Quiet-hours update failed: {}", e.toString())
            message = "Use an IANA time zone and provide both start and end times."
            failed = true
            messageId += 1
        }
    }
}
