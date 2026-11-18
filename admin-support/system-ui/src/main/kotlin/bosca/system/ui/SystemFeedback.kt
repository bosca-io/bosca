package bosca.system.ui

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

@Serializable
class SystemFeedback(
    var message: String = "", var failed: Boolean = false, var nextHref: String = "", var sessionRevoked: Boolean = false,
    val noticeId: String = UUID.randomUUID().toString(), var noticeSequence: Int = 0,
) {
    suspend fun perform(action: suspend () -> Unit) {
        message = ""
        failed = false
        nextHref = ""
        sessionRevoked = false
        try {
            action()
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            failed = true
            message = e.message ?: "The system action failed."
        }
        if (message.isNotBlank()) noticeSequence++
    }
}
