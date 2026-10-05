package bosca.communications.service

import bosca.bml.message.client.RenderPushAttachment
import bosca.bml.message.client.RenderPushAttachmentType
import bosca.bml.message.client.RenderPushAction
import bosca.bml.message.client.RenderPushConversation
import bosca.bml.message.client.RenderPushOptions
import bosca.bml.message.client.RenderPushRichContent
import bosca.bml.message.client.BmlMessageServerClient
import bosca.bml.message.client.RenderRequest
import bosca.bml.message.client.RenderChannel
import bosca.communications.configuration.getBoscaMessageBranding
import bosca.communications.model.BmlMessageHostedProject
import bosca.communications.model.EmailPreview
import bosca.communications.model.MessageBmlTemplate
import bosca.communications.model.PushOptions
import bosca.communications.model.PushAction
import bosca.communications.model.PushAttachment
import bosca.communications.model.PushAttachmentType
import bosca.communications.model.PushConversation
import bosca.communications.model.PushRichContent
import bosca.communications.model.RenderedPushTemplate
import bosca.communications.model.ResolvedMessageTemplate
import bosca.configuration.service.ConfigurationService
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * [BmlMessageTemplateRendererService] over the hosted BML message renderer's typed client
 * (`BML_MESSAGE_SERVER_URL`, cluster-internal). The server derives the version-pinned public
 * asset base for whatever version renders, so delivered HTML references assets that stay
 * servable forever.
 */
@ServiceImplementation
class BmlMessageTemplateRendererServiceImpl(
    private val client: BmlMessageServerClient,
    private val registry: BmlMessageRegistryService,
    private val tokenProvider: BmlMessageServerTokenProvider,
    private val configurationService: ConfigurationService,
    private val json: Json,
) : BmlMessageTemplateRendererService {

    // Inline-image bytes, fetched once per immutable (project, version, source) — the render
    // API ships references so bulk sends don't haul the same base64 through every render.
    private val images = EmailImageCache()

    override suspend fun render(
        template: ResolvedMessageTemplate,
        payload: JsonElement?,
        messageId: String?,
        recipientId: String?,
        recipientName: String?,
        recipientEmail: String?,
        unsubscribeUrl: String?,
        preferencesUrl: String?,
        locale: Locale?,
    ): RenderedEmail {
        val response = client.render(
            template.project,
            template.templateKey,
            RenderRequest(
                channel = RenderChannel.EMAIL,
                messageId = messageId,
                version = template.version,
                recipientId = recipientId,
                recipientName = recipientName,
                recipientEmail = recipientEmail,
                locale = locale?.toLanguageTag(),
                unsubscribeUrl = unsubscribeUrl,
                preferencesUrl = preferencesUrl,
                payload = brandingPayload(template.project, payload),
            ),
            tokenProvider.token(),
        )
        val email = checkNotNull(response.email) {
            "BML message template ${template.project}/${template.templateKey} does not declare an email channel"
        }
        return RenderedEmail(
            subject = email.subject,
            html = email.html,
            text = email.text,
            version = response.version,
            images = email.images.map { image ->
                // The cache holds the finished, immutable attachment value — renders share the
                // instance; nothing is re-encoded or copied per send.
                images.get("${response.project}@${response.version}/${image.source}") {
                    RenderedEmailImage(
                        cid = image.cid,
                        mediaType = image.mediaType,
                        filename = image.filename,
                        contentBase64 = java.util.Base64.getEncoder().encodeToString(
                            client.asset(response.project, response.version, image.source),
                        ),
                    )
                }
            },
        )
    }

    override suspend fun renderPush(
        template: ResolvedMessageTemplate,
        payload: JsonElement?,
        messageId: String?,
        recipientId: String?,
        recipientName: String?,
        recipientEmail: String?,
        pushOptions: PushOptions?,
        locale: Locale?,
    ): RenderedPushTemplate? {
        val response = client.render(
            template.project,
            template.templateKey,
            RenderRequest(
                channel = RenderChannel.PUSH,
                messageId = messageId,
                version = template.version,
                recipientId = recipientId,
                recipientName = recipientName,
                recipientEmail = recipientEmail,
                locale = locale?.toLanguageTag(),
                payload = brandingPayload(template.project, payload),
                pushOptions = pushOptions?.toRender(),
            ),
            tokenProvider.token(),
        )
        return response.push?.let { push ->
            RenderedPushTemplate(
                title = push.title,
                body = push.body,
                options = push.options?.let { options ->
                    PushOptions(
                        imageUrl = options.imageUrl,
                        defaultAction = options.defaultAction?.toModel(),
                        actions = options.actions.map { it.toModel() },
                        priority = options.priority,
                        sound = options.sound,
                        badge = options.badge,
                        ttl = options.ttl,
                        androidChannelId = options.androidChannelId,
                        androidTag = options.androidTag,
                        collapseKey = options.collapseKey,
                        threadId = options.threadId,
                        category = options.category,
                        interruptionLevel = options.interruptionLevel,
                        relevanceScore = options.relevanceScore,
                        mutableContent = options.mutableContent,
                        contentAvailable = options.contentAvailable,
                        data = options.data,
                        richContent = options.richContent?.let { content ->
                            PushRichContent(
                                attachments = content.attachments.map { attachment ->
                                    PushAttachment(
                                        id = attachment.id,
                                        url = attachment.url,
                                        type = PushAttachmentType.valueOf(attachment.type.name),
                                        mediaType = attachment.mediaType,
                                        altText = attachment.altText,
                                    )
                                },
                                conversation = content.conversation?.let { conversation ->
                                    PushConversation(
                                        id = conversation.id,
                                        title = conversation.title,
                                        messageId = conversation.messageId,
                                        senderId = conversation.senderId,
                                        senderName = conversation.senderName,
                                        senderImageUrl = conversation.senderImageUrl,
                                        body = conversation.body,
                                        sentAtEpochMilliseconds = conversation.sentAtEpochMilliseconds,
                                        groupConversation = conversation.groupConversation,
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }
    }

    override suspend fun preview(
        template: MessageBmlTemplate,
        version: String?,
        recipientName: String?,
        recipientEmail: String?,
        locale: Locale?,
    ): EmailPreview {
        val resolved = registry.resolve(template).let { r ->
            version?.takeIf { it.isNotBlank() }?.let { r.copy(version = it) } ?: r
        }
        val rendered = render(
            resolved,
            payload = template.payload,
            recipientName = recipientName,
            recipientEmail = recipientEmail,
            locale = locale,
        )
        // Browser-viewable HTML: cid: references mean nothing outside a MIME message.
        val html = rendered.images.fold(rendered.html) { acc, image ->
            acc.replace("cid:${image.cid}", "data:${image.mediaType};base64,${image.contentBase64}")
        }
        return EmailPreview(
            project = resolved.project,
            templateKey = resolved.templateKey,
            version = rendered.version,
            subject = rendered.subject,
            html = html,
            text = rendered.text,
        )
    }

    override suspend fun hostedProjects(): List<BmlMessageHostedProject> {
        val pins = registry.listProjects().associate { it.key to it.pinnedVersion }
        return client.hostedProjects().map {
            BmlMessageHostedProject(
                project = it.project,
                activeVersion = it.activeVersion,
                pinnedVersion = pins[it.project],
                templates = it.templates.map { template ->
                    bosca.communications.model.BmlMessageTemplateInfo(
                        key = template.key,
                        samplePayload = template.samplePayload,
                        payloadSchema = template.payloadSchema,
                        supportsEmail = template.supportsEmail,
                        supportsPush = template.supportsPush,
                    )
                },
            )
        }
    }

    override suspend fun versions(project: String): List<String> = client.versions(project)

    /**
     * Makes configuration authoritative for the first-party templates while leaving hosted
     * third-party BML projects and their payload contracts untouched.
     */
    private suspend fun brandingPayload(project: String, payload: JsonElement?): JsonElement {
        val source = payload ?: JsonNull
        if (project != BOSCA_MESSAGES_PROJECT || source !is JsonObject) return source

        val branding = configurationService.getBoscaMessageBranding(json)
        return JsonObject(
            source + mapOf(
                "appName" to JsonPrimitive(branding.title),
                "logoUrl" to JsonPrimitive(branding.logoUrl),
                "logoOnly" to JsonPrimitive(branding.logoOnly),
                "primaryColor" to JsonPrimitive(branding.primaryColor),
                "accentColor" to JsonPrimitive(branding.accentColor),
            ),
        )
    }

    private companion object {
        const val BOSCA_MESSAGES_PROJECT = "bosca-messages"
    }

    private fun PushOptions.toRender(): RenderPushOptions = RenderPushOptions(
        imageUrl = imageUrl,
        defaultAction = defaultAction?.toRender(),
        actions = actions.map { it.toRender() },
        priority = priority,
        sound = sound,
        badge = badge,
        ttl = ttl,
        androidChannelId = androidChannelId,
        androidTag = androidTag,
        collapseKey = collapseKey,
        threadId = threadId,
        category = category,
        interruptionLevel = interruptionLevel,
        relevanceScore = relevanceScore,
        mutableContent = mutableContent,
        contentAvailable = contentAvailable,
        data = data,
        richContent = richContent?.let { content ->
            RenderPushRichContent(
                attachments = content.attachments.map { it.toRender() },
                conversation = content.conversation?.let { conversation ->
                    RenderPushConversation(
                        id = conversation.id,
                        title = conversation.title,
                        messageId = conversation.messageId,
                        senderId = conversation.senderId,
                        senderName = conversation.senderName,
                        senderImageUrl = conversation.senderImageUrl,
                        body = conversation.body,
                        sentAtEpochMilliseconds = conversation.sentAtEpochMilliseconds,
                        groupConversation = conversation.groupConversation,
                    )
                },
            )
        },
    )

    private fun PushAttachment.toRender(): RenderPushAttachment = RenderPushAttachment(
        id = id,
        url = url,
        type = RenderPushAttachmentType.valueOf(type.name),
        mediaType = mediaType,
        altText = altText,
    )

    private fun PushAction.toRender(): RenderPushAction = RenderPushAction(
        id = id,
        label = label,
        url = url,
        destructive = destructive,
        data = data,
    )

    private fun RenderPushAction.toModel(): PushAction = PushAction(
        id = id,
        label = label,
        url = url,
        destructive = destructive,
        data = data,
    )
}
