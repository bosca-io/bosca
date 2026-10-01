package bosca.profiles.web

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.profiles.web.graphql.DeleteDevice
import bosca.profiles.web.graphql.RevokeApiToken
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val deviceLog = LoggerFactory.getLogger("bosca.profiles.web.DevicesModel")

@Serializable
class DevicesModel(
    var devices: List<DeviceRow>,
    var tokens: List<ApiTokenRow>,
    val tab: String,
    var message: String? = null,
    var failed: Boolean = false,
    var messageId: Long = 0,
) {
    val statusClass: String get() = if (failed) "status error" else "status ok"
    val devicesTab: Boolean get() = tab != "tokens"
    val tokensTab: Boolean get() = tab == "tokens"

    suspend fun delete(id: String, ctx: RenderContext) {
        try {
            ctx.gql.execute(DeleteDevice, DeleteDevice.Variables(id))
            devices = devices.filterNot { it.id == id }
            message = "Device registration removed."
            failed = false
            messageId += 1
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            deviceLog.warn("Device deletion failed for {}: {}", id, e.toString())
            message = "We couldn't remove that device."
            failed = true
            messageId += 1
        }
    }

    suspend fun revokeToken(id: Long, ctx: RenderContext) {
        try {
            ctx.gql.execute(RevokeApiToken, RevokeApiToken.Variables(id))
            tokens = tokens.map { if (it.id == id) it.copy(active = false, revokedAt = "Revoked now") else it }
            message = "API token revoked."
            failed = false
            messageId += 1
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            deviceLog.warn("API token revocation failed for {}: {}", id, e.toString())
            message = "We couldn't revoke that API token."
            failed = true
            messageId += 1
        }
    }
}
