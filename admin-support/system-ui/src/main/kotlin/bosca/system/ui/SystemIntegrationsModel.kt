package bosca.system.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.system.ui.graphql.SystemIntegrations
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
class SystemIntegrationsModel(
    val admin: Boolean = false,
    var cards: List<SystemIntegrationCard> = emptyList(),
    val feedback: SystemFeedback = SystemFeedback(),
) {
    suspend fun refresh(ctx: RenderContext) = feedback.perform {
        if (!admin) return@perform
        val data = ctx.gql.execute(SystemIntegrations, Unit)
        fun objectValue(value: JsonElement?) = value as? JsonObject ?: JsonObject(emptyMap())
        fun JsonObject.has(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
        val smtp = objectValue(data.smtp.configuration?.value)
        val smtpPort = smtp["port"]?.jsonPrimitive?.takeUnless { it.isString }?.intOrNull ?: if (smtp["port"] == null || smtp["port"] == JsonNull) 587 else 0
        val sendgrid = objectValue(data.sendgrid.configuration?.value)
        val mailgun = objectValue(data.mailgun.configuration?.value)
        val push = objectValue(data.push.configuration?.value)
        val apns = objectValue(push["apns"])
        val hubspot = objectValue(data.hubspot.configuration?.value)
        val mux = objectValue(data.mux.configuration?.value)
        cards = listOf(
            SystemIntegrationCard("smtp", "SMTP", "Email delivery through an SMTP server", "mail", smtp.has("host") && smtpPort in 1..65535),
            SystemIntegrationCard("bible", "Bible providers", "Text Bibles and recordings from external providers", "book-open", data.content.bibleProviders.configurations.any { it.enabled && it.hasApiKey }),
            SystemIntegrationCard("sendgrid", "SendGrid", "Email delivery and signed delivery-event webhooks", "mail", sendgrid.has("apiKey")),
            SystemIntegrationCard("mailgun", "Mailgun", "Email delivery through a verified Mailgun sending domain", "mail", mailgun.has("apiKey") && mailgun.has("domain")),
            SystemIntegrationCard("push", "Push notifications", "Mobile and web delivery through Firebase and APNs", "bell", (push["enabled"] as? JsonPrimitive)?.booleanOrNull == true && (push["fcm"] is JsonObject || listOf("teamId", "keyId", "bundleId", "privateKey").all(apns::has))),
            SystemIntegrationCard("hubspot", "HubSpot", "CRM integration for contact and organization sync", "globe", hubspot.has("token")),
            SystemIntegrationCard("mux", "Mux", "Video hosting, encoding, and streaming", "video", mux.has("tokenId")),
            SystemIntegrationCard("federation", "Federation", "Cross-instance chat through registered Bosca peers", "link", data.collaboration.federation.peers.isNotEmpty()),
        )
    }
}

suspend fun systemIntegrations(): SystemIntegrationsModel {
    val ctx = currentRenderContext()
    return SystemIntegrationsModel(admin = systemAdministrator(ctx)).also { it.refresh(ctx) }
}
