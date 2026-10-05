package bosca.notifications.web

import bosca.bml.graphql.execute
import bosca.bml.render.client
import bosca.notifications.web.graphql.UnsubscribeAll
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(UnsubscribeModel::class.java)

/**
 * One-click unsubscribe — the `/unsubscribe` page's live-island model. [confirm] runs the
 * unsubscribe-all mutation server-side; the island re-renders into the done state, or an
 * inline retry notice when the write failed.
 */
@Serializable
class UnsubscribeModel(
    val token: String = "",
    val invalid: Boolean = false,
    var done: Boolean = false,
    var failed: Boolean = false,
    /** Island re-renders only see the model, so the brand rides in it for the confirm copy. */
    val brand: String = brand(),
) {
    suspend fun confirm() {
        try {
            client().execute(UnsubscribeAll, UnsubscribeAll.Variables(token = token))
            done = true
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Unsubscribe failed: {}", e.toString())
            failed = true
        }
    }
}
