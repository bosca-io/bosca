@file:OptIn(ExperimentalSerializationApi::class)

package bosca.communications.mailers.sendgrid

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SendGridRequest(
    val from: SendGridEmail,
    val subject: String,
    val content: List<SendGridContent>,
    @SerialName("personalizations")
    val personalization: List<Personalization>,
    // NEVER: SendGrid rejects a null/empty attachments array, so the field must be absent
    // whenever there is nothing to attach, regardless of the Json instance's encodeDefaults.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val attachments: List<SendGridAttachment>? = null,
    // ALWAYS: explicitly disable SendGrid's own engagement tracking on EVERY send — a
    // request-level setting overrides account defaults. Click/open tracking is first-party:
    // SendGrid's link rewriting would wrap our /c/<token> redirects in a
    // sendgrid.net hop and its pixel would double-count opens through the webhook; its
    // subscription footer would fight the minted unsubscribe links. The webhook keeps owning
    // what only the provider can see: delivered/bounce/deferred/dropped/spam.
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("tracking_settings")
    val trackingSettings: SendGridTrackingSettings = SendGridTrackingSettings(),
)

/** Per-request tracking overrides — all provider-side engagement tracking off. */
@Serializable
data class SendGridTrackingSettings(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("click_tracking")
    val clickTracking: SendGridClickTracking = SendGridClickTracking(),
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("open_tracking")
    val openTracking: SendGridTrackingToggle = SendGridTrackingToggle(),
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("subscription_tracking")
    val subscriptionTracking: SendGridTrackingToggle = SendGridTrackingToggle(),
)

@Serializable
data class SendGridClickTracking(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val enable: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("enable_text")
    val enableText: Boolean = false,
)

@Serializable
data class SendGridTrackingToggle(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val enable: Boolean = false,
)

/** One inline (`disposition=inline` + `content_id`) attachment of a SendGrid send. */
@Serializable
data class SendGridAttachment(
    /** Base64 file bytes. */
    val content: String,
    val type: String,
    val filename: String,
    // ALWAYS: SendGrid's default disposition is "attachment" — omitting the field would turn
    // an embedded image into a visible file attachment.
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val disposition: String = "inline",
    @SerialName("content_id")
    val contentId: String,
)