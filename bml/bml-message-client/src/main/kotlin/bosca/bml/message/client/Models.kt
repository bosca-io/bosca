package bosca.bml.message.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * The render API's wire contract — the single source both the server's
 * routes and this client serialize against.
 */
@Serializable
data class RenderRequest(
    /** Restricts evaluation to the channel the caller intends to deliver. */
    val channel: RenderChannel? = null,
    val recipientId: String? = null,
    /** The message id engagement tracking attributes clicks/opens to; null disables rewriting. */
    val messageId: String? = null,
    /**
     * Explicit published version to render (registry pin / rollback — data-driven, no redeploy).
     * Null renders the active (latest published) version. An unknown version fails typed (404).
     */
    val version: String? = null,
    val recipientName: String? = null,
    val recipientEmail: String? = null,
    /**
     * The recipient's locale (their `bosca.profiles.locale` setting). Null lets the server
     * render in its localization project's source language.
     */
    val locale: String? = null,
    val timezone: String? = null,
    val senderName: String? = null,
    val senderEmail: String? = null,
    val unsubscribeUrl: String? = null,
    val preferencesUrl: String? = null,
    /** Absolute asset base override; omitted = the server derives a version-pinned base. */
    val assetsUrl: String? = null,
    val payload: JsonElement = JsonNull,
    /** Delivery metadata supplied by the caller for a companion `<push>` region. */
    val pushOptions: RenderPushOptions? = null,
)

/** A single output channel requested from a hosted BML message template. */
@Serializable
enum class RenderChannel {
    EMAIL,
    PUSH,
}

@Serializable
data class RenderResponse(
    val project: String,
    val templateKey: String,
    /** The artifact version that rendered — pinned into the asset URLs the HTML references. */
    val version: String,
    /** Email-channel output when the BML message declares `<email>`. */
    val email: RenderEmail? = null,
    /** Push-channel output when the BML message declares `<push>`. */
    val push: RenderPush? = null,
)

/** Provider-ready email output returned as one channel of a rendered BML message. */
@Serializable
data class RenderEmail(
    val subject: String,
    val html: String,
    val text: String,
    /** `bml-inline` images the render used; the mailer attaches each as an inline part. */
    val images: List<RenderImage> = emptyList(),
)

/** Provider-ready push output returned as one channel of a rendered BML message. */
@Serializable
data class RenderPush(
    val title: String,
    val body: String,
    val options: RenderPushOptions? = null,
)

/** Wire representation of provider-neutral BML push options. */
@Serializable
data class RenderPushOptions(
    val imageUrl: String? = null,
    val defaultAction: RenderPushAction? = null,
    val actions: List<RenderPushAction> = emptyList(),
    val priority: String? = null,
    val sound: String? = null,
    val badge: Int? = null,
    val ttl: Long? = null,
    val androidChannelId: String? = null,
    val androidTag: String? = null,
    val collapseKey: String? = null,
    val threadId: String? = null,
    val category: String? = null,
    val interruptionLevel: String? = null,
    val relevanceScore: Double? = null,
    val mutableContent: Boolean? = null,
    val contentAvailable: Boolean? = null,
    val data: Map<String, String>? = null,
    val richContent: RenderPushRichContent? = null,
)

@Serializable
data class RenderPushAction(
    val id: String,
    val label: String? = null,
    val url: String? = null,
    val destructive: Boolean = false,
    val data: Map<String, String>? = null,
)

/** Wire representation of structured rich-notification content. */
@Serializable
data class RenderPushRichContent(
    val attachments: List<RenderPushAttachment> = emptyList(),
    val conversation: RenderPushConversation? = null,
)

@Serializable
data class RenderPushAttachment(
    val id: String? = null,
    val url: String,
    val type: RenderPushAttachmentType = RenderPushAttachmentType.IMAGE,
    val mediaType: String? = null,
    val altText: String? = null,
)

@Serializable
enum class RenderPushAttachmentType {
    IMAGE,
    VIDEO,
    AUDIO,
}

@Serializable
data class RenderPushConversation(
    val id: String,
    val title: String? = null,
    val messageId: String,
    val senderId: String? = null,
    val senderName: String? = null,
    val senderImageUrl: String? = null,
    val body: String,
    val sentAtEpochMilliseconds: Long? = null,
    val groupConversation: Boolean = false,
)

/**
 * A `bml-inline` image REFERENCE: the HTML references `cid:[cid]`; the bytes are fetched from
 * the version-pinned asset route (`/assets/{project}/{version}/{source}` — the render response
 * carries the project/version) via [BmlMessageServerClient.asset]. Deliberately not the bytes:
 * a published version's assets are immutable, so the send side caches them once per
 * (project, version, source) instead of hauling base64 through every render of a bulk send.
 */
@Serializable
data class RenderImage(
    val cid: String,
    /** The bundle-relative asset path inside the project jar — the fetch/cache key with (project, version). */
    val source: String,
    val mediaType: String,
    val filename: String,
)

@Serializable
data class RenderError(val error: String)

/**
 * A project the message server currently hosts: its active (latest published) version and the
 * templates of that version. The dropdown source of truth — includes projects that were never
 * registered in the platform registry (registration is optional).
 */
@Serializable
data class HostedMessageProject(
    val project: String,
    val activeVersion: String,
    val templates: List<HostedMessageTemplate>,
)

/**
 * One template of a hosted project. [samplePayload] is the payload skeleton derived from the
 * template's declared payload serializer — what authoring surfaces pre-fill so authors see
 * the structure instead of decoding "field X is required" errors; null when the template
 * takes no payload (or didn't declare one recognizably). [payloadSchema] is the same
 * serializer projected as a JSON-Schema subset (`type`/`properties`/`required`/`items`/`enum`)
 * — the machine-readable contract authoring surfaces render per field.
 */
@Serializable
data class HostedMessageTemplate(
    val key: String,
    val samplePayload: JsonElement? = null,
    val payloadSchema: JsonElement? = null,
    /** True when this BML message unit declares email output. */
    val supportsEmail: Boolean = true,
    /** True when this BML message unit declares push output. */
    val supportsPush: Boolean = false,
)
