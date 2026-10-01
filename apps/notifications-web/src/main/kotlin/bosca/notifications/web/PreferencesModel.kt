package bosca.notifications.web

import bosca.bml.graphql.execute
import bosca.bml.render.client
import bosca.notifications.web.graphql.DeliveryChannel
import bosca.notifications.web.graphql.UnsubscribeAll
import bosca.notifications.web.graphql.UpdateTokenPreference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(PreferencesModel::class.java)

/**
 * The preference matrix — the `/preferences` page's live-island model. `@click` runs the
 * per-channel toggles / [unsubscribeAll] server-side with the page's token and the island
 * re-renders the matrix with the new state. A failed write surfaces as an inline status
 * message; the matrix keeps its last-saved state.
 */
@Serializable
class PreferencesModel(
    val token: String = "",
    var rows: List<TypeRow> = emptyList(),
    val invalid: Boolean = false,
    var message: String? = null,
    var failed: Boolean = false,
) {
    /** The status line's CSS class — referenced from the page markup. */
    val statusClass: String get() = if (failed) "status error" else "status ok"

    suspend fun toggleEmail(key: String) = toggle(key, CHANNEL_EMAIL)

    suspend fun togglePush(key: String) = toggle(key, CHANNEL_PUSH)

    private suspend fun toggle(key: String, channel: String) {
        val row = rows.firstOrNull { it.key == key }
        if (row == null || !row.optional) return
        val push = channel == CHANNEL_PUSH
        val next = if (push) !row.pushOptedOut else !row.emailOptedOut
        val what = if (push) "push notifications" else "email"
        try {
            client().execute(
                UpdateTokenPreference,
                UpdateTokenPreference.Variables(
                    token = token,
                    type = key,
                    optedOut = next,
                    channel = if (push) DeliveryChannel.PUSH else DeliveryChannel.EMAIL,
                ),
            )
            rows = toggled(rows, key, channel)
            message = if (next) {
                "You won't receive \"${row.name}\" $what anymore."
            } else {
                "You'll receive \"${row.name}\" $what."
            }
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Preference toggle failed for type {} channel {}: {}", key, channel, e.toString())
            message = "We couldn't save that change — please try again."
            failed = true
        }
    }

    suspend fun unsubscribeAll() {
        try {
            client().execute(UnsubscribeAll, UnsubscribeAll.Variables(token = token))
            rows = allOptionalEmailOff(rows)
            message = "You're unsubscribed from all optional email."
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Unsubscribe-all failed: {}", e.toString())
            message = "We couldn't unsubscribe you — please try again."
            failed = true
        }
    }
}
