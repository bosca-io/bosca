package bosca.bml.message.server

import bosca.bml.message.BmlMessageArtifacts
import bosca.bml.message.BmlMessageChannel
import bosca.bml.message.BmlMessageContext
import bosca.bml.message.BmlPushAttachment
import bosca.bml.message.BmlPushAttachmentType
import bosca.bml.message.BmlPushAction
import bosca.bml.message.BmlPushConversation
import bosca.bml.message.BmlPushOptions
import bosca.bml.message.BmlPushRichContent
import bosca.bml.message.client.RenderError
import bosca.bml.message.client.RenderEmail
import bosca.bml.message.client.RenderImage
import bosca.bml.message.client.RenderPush
import bosca.bml.message.client.RenderPushAttachment
import bosca.bml.message.client.RenderPushAttachmentType
import bosca.bml.message.client.RenderPushAction
import bosca.bml.message.client.RenderPushConversation
import bosca.bml.message.client.RenderPushOptions
import bosca.bml.message.client.RenderPushRichContent
import bosca.bml.message.client.RenderRequest
import bosca.bml.message.client.RenderResponse
import bosca.bml.message.host.BmlMessageHostException
import bosca.bml.graphql.GraphQLClientFactory
import bosca.server.BoscaApplication
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.Locale
import kotlin.time.Duration

/**
 * The PRIVATE render API: `POST /render/{project}/{template}` with a
 * [RenderRequest] body → [RenderResponse] JSON. Cluster-internal only — deployments must never
 * route it publicly (the asset surface is the only public exposure). Renders are bounded by a
 * timeout so a misbehaving template can't wedge the send path.
 */
object RenderApi {

    private val json = Json { ignoreUnknownKeys = true }
    private val log = LoggerFactory.getLogger(RenderApi::class.java)

    fun install(
        application: BoscaApplication,
        projects: MessageProjects,
        cache: MessageJarCache,
        publicBaseUrl: String,
        renderTimeout: Duration,
        tracking: LinkTracking?,
        /** Creates the request-bound GraphQL client that forwards the caller's bearer token. */
        graphqlClientFactory: GraphQLClientFactory,
        /**
         * Localized strings for renders — the server's cached localization binding.
         * The empty default keeps unlocalized deployments byte-for-byte unchanged.
         */
        messageSource: bosca.bml.i18n.MessageSource = bosca.bml.i18n.MessageSource.Empty,
    ) {
        val router = application.router

        router.post("/render/{project}/{template}") {
            val bearerToken = call.request.header("Authorization")
                ?.takeIf { it.startsWith("Bearer ") && it.removePrefix("Bearer ").isNotBlank() }
            if (bearerToken == null) {
                call.respondBytes(
                    errorBytes("Authorization bearer token is required"),
                    ContentType.Application.Json,
                    HttpStatusCode.Unauthorized,
                )
                return@post
            }
            val project = call.pathParameters["project"].orEmpty()
            val template = call.pathParameters["template"].orEmpty()
            val request = try {
                json.decodeFromString(RenderRequest.serializer(), call.request.bodyText().ifBlank { "{}" })
            } catch (e: Exception) {
                call.respondBytes(errorBytes("invalid render request: ${e.message}"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }
            val activeVersion = projects.activeVersion(project)
            if (activeVersion == null) {
                call.respondBytes(errorBytes("message project '$project' has no active version"), ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                return@post
            }
            // Explicit version = the registry's pin/rollback path; null renders the active one.
            val version = request.version ?: activeVersion
            val context = BmlMessageContext(
                channel = request.channel?.let { BmlMessageChannel.valueOf(it.name) },
                recipientId = request.recipientId,
                recipientName = request.recipientName,
                recipientEmail = request.recipientEmail,
                // No explicit recipient locale renders in the localization project's source
                // language (the unconfigured source's default stays "en" — behavior unchanged).
                locale = request.locale?.let { Locale.forLanguageTag(it).toLanguageTag() }
                    ?: messageSource.defaultLocale.toLanguageTag(),
                timezone = request.timezone,
                senderName = request.senderName,
                senderEmail = request.senderEmail,
                unsubscribeUrl = request.unsubscribeUrl,
                preferencesUrl = request.preferencesUrl,
                // Version-pinned by the version actually rendering: the produced HTML references
                // assets that stay servable forever, even after later publishes.
                assetsUrl = request.assetsUrl ?: "${publicBaseUrl.trimEnd('/')}/assets/$project/$version",
                payload = request.payload,
                gql = graphqlClientFactory.forToken(bearerToken),
                token = bearerToken,
                messages = messageSource,
                pushOptions = request.pushOptions?.toBml(),
            )
            try {
                val rendered = withTimeout(renderTimeout) {
                    projects.render(project, template, context, request.version)
                }
                val renderedEmail = rendered.email
                // First-party engagement tracking: with a message id and a configured
                // tracker, content links rewrite to /c/<token> and the open pixel is injected.
                // Unsubscribe/preferences links are never rewritten (deliverability), and the
                // plain-text alternative keeps its original links.
                val html = if (renderedEmail != null && tracking != null && request.messageId != null) {
                    tracking.rewrite(
                        renderedEmail.html,
                        messageId = request.messageId!!,
                        recipientId = request.recipientId,
                        excludeUrls = setOfNotNull(request.unsubscribeUrl, request.preferencesUrl),
                    )
                } else {
                    renderedEmail?.html.orEmpty()
                }
                // The render's bml-inline images ship as REFERENCES — the send side fetches the
                // immutable bytes from the version-pinned asset route and caches them once per
                // (project, version, source) instead of receiving base64 in every render of a
                // bulk send. Presence is still validated here: a referenced asset the bundle
                // doesn't have is a template bug and fails typed BEFORE anything sends.
                val images = if (renderedEmail == null || renderedEmail.images.isEmpty()) emptyList() else {
                    val jar = cache.jarFor(project, version)
                    java.util.jar.JarFile(jar).use { jarFile ->
                        renderedEmail.images.map { image ->
                            jarFile.getJarEntry("${BmlMessageArtifacts.ASSETS_RESOURCE_ROOT}/${image.source}")
                                ?.takeIf { !it.isDirectory }
                                ?: throw BmlMessageHostException(
                                    "bml-inline image '${image.source}' is not in $project@$version's bundle",
                                )
                            RenderImage(
                                cid = image.cid,
                                source = image.source,
                                mediaType = mediaTypeFor(image.source),
                                filename = image.source.substringAfterLast('/'),
                            )
                        }
                    }
                }
                val push = rendered.push?.let {
                    RenderPush(
                        title = it.title,
                        body = it.body,
                        options = it.options?.toRender(),
                    )
                }
                val body = RenderResponse(
                    project = project,
                    templateKey = template,
                    version = version,
                    email = renderedEmail?.let {
                        RenderEmail(
                            subject = it.subject,
                            html = html,
                            text = it.text,
                            images = images,
                        )
                    },
                    push = push,
                )
                call.respondBytes(
                    json.encodeToString(RenderResponse.serializer(), body).toByteArray(Charsets.UTF_8),
                    ContentType.Application.Json,
                )
            } catch (e: BmlMessageHostException) {
                call.respondBytes(errorBytes(e.message ?: "unknown template"), ContentType.Application.Json, HttpStatusCode.NotFound)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                log.error("bml-message: render timed out: {}/{} after {}", project, template, renderTimeout)
                call.respondBytes(errorBytes("render timed out after $renderTimeout"), ContentType.Application.Json, HttpStatusCode.InternalServerError)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("bml-message: render failed: {}/{}", project, template, e)
                call.respondBytes(errorBytes("render failed: ${e.message}"), ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        }

    }

    private fun errorBytes(message: String): ByteArray =
        json.encodeToString(RenderError.serializer(), RenderError(message)).toByteArray(Charsets.UTF_8)

    private fun mediaTypeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }

    private fun RenderPushOptions.toBml(): BmlPushOptions = BmlPushOptions(
        imageUrl = imageUrl,
        defaultAction = defaultAction?.toBml(),
        actions = actions.map { it.toBml() },
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
            BmlPushRichContent(
                attachments = content.attachments.map { it.toBml() },
                conversation = content.conversation?.let { conversation ->
                    BmlPushConversation(
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

    private fun RenderPushAttachment.toBml(): BmlPushAttachment = BmlPushAttachment(
        id = id,
        url = url,
        type = BmlPushAttachmentType.valueOf(type.name),
        mediaType = mediaType,
        altText = altText,
    )

    private fun RenderPushAction.toBml(): BmlPushAction = BmlPushAction(
        id = id,
        label = label,
        url = url,
        destructive = destructive,
        data = data,
    )

    private fun BmlPushOptions.toRender(): RenderPushOptions = RenderPushOptions(
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

    private fun BmlPushAttachment.toRender(): RenderPushAttachment = RenderPushAttachment(
        id = id,
        url = url,
        type = RenderPushAttachmentType.valueOf(type.name),
        mediaType = mediaType,
        altText = altText,
    )

    private fun BmlPushAction.toRender(): RenderPushAction = RenderPushAction(
        id = id,
        label = label,
        url = url,
        destructive = destructive,
        data = data,
    )
}
