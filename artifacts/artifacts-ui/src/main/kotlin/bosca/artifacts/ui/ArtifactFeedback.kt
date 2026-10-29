package bosca.artifacts.ui

import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

@Serializable
class ArtifactFeedback(
    var message: String = "",
    var failed: Boolean = false,
    var nextHref: String = "",
    var runHref: String = "",
) {
    suspend fun perform(block: suspend () -> Unit) {
        message = ""
        failed = false
        nextHref = ""
        runHref = ""
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            failed = true
            message = e.message ?: "The artifact action failed."
        }
    }
}
