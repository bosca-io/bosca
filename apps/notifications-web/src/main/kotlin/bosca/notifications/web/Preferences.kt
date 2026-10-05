package bosca.notifications.web

import bosca.bml.graphql.execute
import bosca.bml.render.client
import bosca.bml.render.currentRenderContext
import bosca.notifications.web.graphql.TokenPreferences
import kotlinx.serialization.Serializable

/**
 * One notification type on the preference matrix: the catalog entry plus this recipient's
 * per-channel opt-out state. Non-[optional] types render as "Always on" — they cannot be
 * toggled on any channel.
 */
@Serializable
data class TypeRow(
    val key: String,
    val name: String,
    val description: String = "",
    val optional: Boolean = true,
    val emailOptedOut: Boolean = false,
    val pushOptedOut: Boolean = false,
)

/** The channel names the matrix manages — must match the API's `DeliveryChannel` values. */
internal const val CHANNEL_EMAIL = "EMAIL"
internal const val CHANNEL_PUSH = "PUSH"

/** [rows] with the [key] row's [channel] opt-out flipped; non-optional rows are never changed. */
internal fun toggled(rows: List<TypeRow>, key: String, channel: String): List<TypeRow> =
    rows.map {
        when {
            it.key != key || !it.optional -> it
            channel == CHANNEL_PUSH -> it.copy(pushOptedOut = !it.pushOptedOut)
            else -> it.copy(emailOptedOut = !it.emailOptedOut)
        }
    }

/** [rows] with every optional row's EMAIL opted out — the matrix after an unsubscribe-all. */
internal fun allOptionalEmailOff(rows: List<TypeRow>): List<TypeRow> =
    rows.map { if (it.optional) it.copy(emailOptedOut = true) else it }

/**
 * What the `/preferences` loader hands the page: the island model is then CONSTRUCTED from this
 * in the page's `provides` (the BML compiler only recognizes a constructor-typed `provides` as a
 * live state model).
 */
class PreferencesPage(
    val token: String = "",
    val rows: List<TypeRow> = emptyList(),
    val invalid: Boolean = false,
)

/** The `/unsubscribe` loader's page state — same constructor-typed-model story as [PreferencesPage]. */
class UnsubscribePage(
    val token: String = "",
    val invalid: Boolean = false,
)

/**
 * Loads `/preferences`: the token from the query string, then the type catalog + the recipient's
 * all-channel preferences by token. A blank or unknown token yields the invalid-link notice (the
 * server answers null preferences for a token it doesn't recognize — that is a state, not an
 * error).
 */
suspend fun preferences(): PreferencesPage {
    val token = currentRenderContext().query["token"].orEmpty()
    if (token.isBlank()) return PreferencesPage(invalid = true)
    val data = client().execute(TokenPreferences, TokenPreferences.Variables(token = token)).communications
    val prefs = data.tokenNotificationPreferences ?: return PreferencesPage(invalid = true)
    val optedOut = prefs.associate { (it.channel.toString() to it.type) to it.optedOut }
    return PreferencesPage(
        token = token,
        rows = data.notificationTypes
            .sortedBy { it.displayOrder }
            .map {
                TypeRow(
                    key = it.key,
                    name = it.name,
                    description = it.description.orEmpty(),
                    optional = it.optional,
                    emailOptedOut = optedOut[CHANNEL_EMAIL to it.key] == true,
                    pushOptedOut = optedOut[CHANNEL_PUSH to it.key] == true,
                )
            },
    )
}

/**
 * Loads `/unsubscribe`: validates the token the same way (so a dead link says so up front
 * instead of failing on the confirm click); the page itself confirms before unsubscribing.
 */
suspend fun unsubscribe(): UnsubscribePage {
    val token = currentRenderContext().query["token"].orEmpty()
    if (token.isBlank()) return UnsubscribePage(invalid = true)
    val prefs = client().execute(TokenPreferences, TokenPreferences.Variables(token = token))
        .communications.tokenNotificationPreferences
    return if (prefs == null) UnsubscribePage(invalid = true) else UnsubscribePage(token = token)
}
