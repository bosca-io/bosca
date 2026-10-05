package bosca.communications.service

import bosca.communications.model.BmlMessageHostedProject
import bosca.communications.model.EmailPreview
import bosca.communications.model.MessageBmlTemplate
import bosca.communications.model.PushOptions
import bosca.communications.model.RenderedPushTemplate
import bosca.communications.model.ResolvedMessageTemplate
import bosca.service.Service
import kotlinx.serialization.json.JsonElement
import java.util.Locale

/**
 * Renders channels from a resolved BML message template at send time. The hosted message server
 * keeps compiled message-project jars active and hot-reloads on publish, so a render here
 * uses the project's active version unless the [ResolvedMessageTemplate] carries a registry pin.
 *
 * A failed render throws with the server's reason; the send path records it as a FAILED
 * delivery event and never sends a malformed email.
 */
interface BmlMessageTemplateRendererService : Service {

    /**
     * Render [template] for one recipient. [recipientName]/[recipientEmail] personalize the
     * standard context (optional clauses in templates degrade gracefully when null); the
     * template-typed [payload] passes through as JSON. [messageId] enables first-party
     * click/open tracking; [unsubscribeUrl]/[preferencesUrl] reach the template AND are
     * excluded from link rewriting (deliverability).
     */
    suspend fun render(
        template: ResolvedMessageTemplate,
        payload: JsonElement? = null,
        messageId: String? = null,
        recipientId: String? = null,
        recipientName: String? = null,
        recipientEmail: String? = null,
        unsubscribeUrl: String? = null,
        preferencesUrl: String? = null,
        /** The recipient's `bosca.profiles.locale` setting; null renders the project's source language. */
        locale: Locale? = null,
    ): RenderedEmail

    /** Render the optional push channel for one recipient with the same personalized context as email. */
    suspend fun renderPush(
        template: ResolvedMessageTemplate,
        payload: JsonElement? = null,
        messageId: String? = null,
        recipientId: String? = null,
        recipientName: String? = null,
        recipientEmail: String? = null,
        pushOptions: PushOptions?,
        locale: Locale?,
    ): RenderedPushTemplate?

    /**
     * Render [template] for PREVIEW — the production resolution + render
     * path (a registry pin applies; [version] overrides it to inspect a specific published
     * version) minus send-only concerns: no message id (no click-tracking rewrite), no minted
     * unsubscribe links. The returned HTML is browser-viewable: `bml-inline` images swap their
     * `cid:` references for `data:` URIs.
     */
    suspend fun preview(
        template: MessageBmlTemplate,
        version: String? = null,
        recipientName: String? = null,
        recipientEmail: String? = null,
        /** Preview locale; null previews the localization project's source language. */
        locale: Locale? = null,
    ): EmailPreview

    /** Projects hosted by the message server, including active versions, pins, and templates. */
    suspend fun hostedProjects(): List<BmlMessageHostedProject>

    /** A hosted project's PUBLISHED versions, newest first — the pin/override choices. */
    suspend fun versions(project: String): List<String>
}
