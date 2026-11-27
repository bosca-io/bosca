package bosca.workops.ui

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** Retains action feedback while cancellation continues to the owning request. */
@Serializable
class WorkOpsFeedback(
    var message: String = "", var failed: Boolean = false,
    val noticeId: String = UUID.randomUUID().toString(), var noticeSequence: Int = 0,
) {
    suspend fun perform(block: suspend () -> Unit) {
        message = ""
        failed = false
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failed = true; message = e.message?.takeIf(String::isNotBlank) ?: "The WorkOps action failed." }
        if (!failed && message.isNotBlank()) noticeSequence++
    }

    /** Issues a successful notice for an action that owns its existing error handling. */
    fun success(text: String) {
        message = text
        failed = false
        noticeSequence++
    }
}
