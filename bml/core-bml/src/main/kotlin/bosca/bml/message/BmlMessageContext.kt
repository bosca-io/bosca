package bosca.bml.message

import bosca.bml.graphql.GraphQLClient
import bosca.bml.i18n.MessageSource
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * The typed input shared by every channel in a BML message. The message producer supplies
 * recipient and sender identity, locale, common links, a caller-bound GraphQL client, the typed
 * payload as JSON, localization messages, and provider-neutral push delivery options.
 */
class BmlMessageContext(
    /** Restricts rendering to one channel; null renders every channel declared by the template. */
    val channel: BmlMessageChannel? = null,
    val recipientId: String? = null,
    val recipientName: String? = null,
    val recipientEmail: String? = null,
    val locale: String = "en",
    val timezone: String? = null,
    val senderName: String? = null,
    val senderEmail: String? = null,
    val unsubscribeUrl: String? = null,
    val preferencesUrl: String? = null,
    /** Absolute base URL for assets referenced by rendered channels. */
    val assetsUrl: String? = null,
    /** The template-specific payload, decoded inside the compiled template with [payload]. */
    val payload: JsonElement = JsonNull,
    /** Caller-token-bound data-plane client, when the host provides one. */
    val gql: GraphQLClient? = null,
    val token: String? = null,
    /** Localized strings bound to [locale] for this render. */
    val messages: MessageSource = MessageSource.Empty,
    /** Producer-owned push routing, grouping, media, and bounded conversation data. */
    val pushOptions: BmlPushOptions? = null,
) {
    /** Decode [payload] with an explicit serializer and no reflective lookup. */
    fun <T> payload(deserializer: DeserializationStrategy<T>): T =
        json.decodeFromJsonElement(deserializer, payload)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
