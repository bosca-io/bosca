package bosca.segmentation.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Typed representation of a push campaign's content JSON payload.
 *
 * Mirrors the fields serialized by the admin UI's content editor for the PUSH channel.
 * Used to deserialize the campaign's [Campaign.content] field so that push-specific
 * options can be mapped to the message pipeline without manual JSON traversal.
 */
@Serializable
data class PushCampaignContent(
    val title: String? = null,
    val body: String? = null,
    val imageId: String? = null,
    val imageName: String? = null,
    val defaultAction: PushCampaignAction? = null,
    val actions: List<PushCampaignAction> = emptyList(),
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
    val data: Map<String, String>? = null
)

/** Provider-neutral action configured for a push campaign. */
@Serializable
data class PushCampaignAction(
    val id: String,
    val label: String? = null,
    val url: String? = null,
    val destructive: Boolean = false,
    val data: Map<String, String>? = null,
)

/**
 * Typed representation of an email campaign's BML template reference and payload.
 *
 * [project], [templateKey], and [payload] are forwarded to the communications service so the
 * BML message template's email channel is rendered at send time. The legacy body fields remain
 * readable so already-scheduled campaigns created before BML message templates continue to deliver.
 */
@Serializable
data class EmailCampaignContent(
    val project: String? = null,
    val templateKey: String? = null,
    val payload: JsonElement? = null,
    val subject: String? = null,
    val textBody: String? = null,
    val htmlBody: String? = null
)
