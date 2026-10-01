package bosca.communications.service

import bosca.devices.model.Device
import bosca.devices.model.PlatformType
import bosca.devices.model.PushToken
import bosca.devices.model.PushProvider
import bosca.devices.service.DeviceService
import bosca.di.annotation.ProviderName
import bosca.db.connectionOrNull
import bosca.graphql.Batch
import bosca.communications.configuration.JobQueueNames
import bosca.communications.jobs.MessageJob
import bosca.communications.jobs.RecordDeliveryEventsJob
import bosca.communications.jobs.RecordDeliveryEventsJobExecutor
import bosca.communications.mailers.ContentType
import bosca.communications.mailers.DeliveryTrackingArguments
import bosca.communications.mailers.InlineImage
import bosca.communications.mailers.Mailer
import bosca.communications.mailers.MailerConfiguration
import bosca.communications.model.DeliveryChannel
import bosca.communications.model.DeliveryEvent
import bosca.communications.model.DeliveryStatusType
import bosca.communications.model.BmlMessageTemplateRender
import bosca.communications.model.Message
import bosca.communications.model.MessageChannel
import bosca.communications.model.MessageContentType
import bosca.communications.model.NotificationType
import bosca.communications.model.PushOptions
import bosca.communications.model.ResolvedMessageTemplate
import bosca.communications.push.PushPlatform
import bosca.communications.push.PushSender
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.attribute.model.getAttributeString
import bosca.profile.profile.service.ProfileService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.FailException
import bosca.sharedqueue.jobs.enqueue
import bosca.sharedqueue.jobs.enqueueLater
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.uuid.ExperimentalUuidApi

@ServiceImplementation
class MessageServiceImpl(
    private val profileService: ProfileService,
    private val deviceService: DeviceService,
    @ProviderName(name = JobQueueNames.messagesJobQueue)
    private val messageQueue: JobQueue,
    private val mailerConfiguration: MailerConfiguration,
    private val mailer: Mailer,
    private val sender: PushSender,
    private val deliveryTracking: DeliveryTrackingService,
    private val gate: NotificationPreferenceGate,
    private val bmlMessageRenderer: BmlMessageTemplateRendererService,
    private val bmlMessageRegistry: BmlMessageRegistryService,
    private val notificationPreferences: NotificationPreferenceService,
    private val notificationTypes: NotificationTypeService,
) : MessageService {

    companion object {
        private val log = LoggerFactory.getLogger(MessageServiceImpl::class.java)
        private const val NOTIFICATION_TYPE_DATA_KEY = "notification_type"
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun send(message: Message) {
        for (deliveryMessage in message.partitionForAsyncDelivery()) {
            deliveryMessage.enqueue(messageQueue, MessageJob::class)
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun sendNow(message: Message) {
        validate(message)
        check(connectionOrNull()?.inTransaction != true) {
            "MessageService.sendNow cannot run inside a database transaction; use send instead"
        }
        require(
            MessageChannel.EMAIL !in message.channels ||
                message.bmlTemplate != null ||
                message.recipients.size <= mailer.maxRecipientsPerMessage,
        ) {
            "The configured mail provider supports at most ${mailer.maxRecipientsPerMessage} recipients per message"
        }
        message.channels.distinct().forEach { channel ->
            when (channel) {
                MessageChannel.EMAIL -> {
                    if (message.bmlTemplate != null && message.recipients.size > 1) {
                        message.recipients.forEach { recipient ->
                            sendToMailer(message.copy(channels = listOf(channel), recipients = listOf(recipient)))
                        }
                    } else {
                        sendToMailer(message.copy(channels = listOf(channel)))
                    }
                }
                MessageChannel.PUSH -> sendToPush(message)
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun sendToMailer(message: Message) {
        val from = mailer.newEmail(mailerConfiguration.from.name, mailerConfiguration.from.email)
        val profiles = message.recipients.map { profileService.getById(it) }
        val attributes = Batch<UUID, List<ProfileAttribute>>(message.recipients)
        profileService.addAttributesToBatch(attributes)
        val included = mutableListOf<UUID>()
        var personalization: Pair<String, String>? = null // first eligible recipient's (name, email)
        var recipientLocale: Locale? = null // first eligible recipient's bosca.profiles.locale setting
        val to = profiles.mapNotNull {
            val attrs = attributes.getData(it.id) ?: emptyList()
            val name = attrs.getAttributeString("bosca.profiles.name", "name") ?: it.name
            val email = attrs.getAttributeString("bosca.profiles.email", "email") ?: return@mapNotNull null
            val locale = attrs.getAttributeString("bosca.profiles.locale", "locale")?.let(Locale::forLanguageTag)
            when (val decision = gate.evaluate(it.id, DeliveryChannel.EMAIL, message.type, email)) {
                is GateDecision.Suppressed -> {
                    log.info("Suppressing email to {} for message {}: {}", it.id, message.id, decision.reason)
                    recordGateEvent(message.id, it.id, DeliveryChannel.EMAIL, DeliveryStatusType.DROPPED, decision.reason)
                    return@mapNotNull null
                }
                // The gate never defers email; treat an unexpected deferral as allow.
                is GateDecision.Deferred, GateDecision.Allow -> Unit
            }
            included.add(it.id)
            if (personalization == null) {
                personalization = name to email
                recipientLocale = locale
            }
            mailer.newEmail(
                name,
                email,
                mapOf(DeliveryTrackingArguments.RECIPIENT_ID to it.id.toString()),
            )
        }
        if (to.isEmpty()) {
            log.info("No eligible recipients for message {}", message.id)
            return
        }
        // A BML message template's email channel renders AFTER the preference gate (a fully suppressed
        // message costs no render) and replaces the stored subject/content. sendNow partitions a
        // templated email to one recipient before entering this method, so every render can safely
        // carry that recipient's identity, locale, and preference links.
        // A failed render records FAILED and rethrows — the job retries; no malformed email sends.
        var bmlTemplateRender: BmlMessageTemplateRender? = null
        val (subject, contents, inlineImages) = message.bmlTemplate?.let { template ->
            val recipient = checkNotNull(personalization)
            val recipientId = included.single()
            var resolvedTemplate: ResolvedMessageTemplate? = null
            val rendered = try {
                // Resolve through the BML message registry: a project reference receives its pin.
                // to its (project, template) — unknown keys fail typed here, never a silent
                // substitute — and a registered project contributes its version pin.
                val resolved = bmlMessageRegistry.resolve(template)
                resolvedTemplate = resolved
                // mint an unsubscribe token so the template's unsubscribe/preferences
                // links authenticate without login.
                val (unsubscribeUrl, preferencesUrl) =
                    mintPreferenceUrls(recipientId, message.type)
                bmlMessageRenderer.render(
                    resolved,
                    payload = template.payload,
                    messageId = message.id.toString(),
                    recipientId = recipientId.toString(),
                    recipientName = recipient.first,
                    recipientEmail = recipient.second,
                    unsubscribeUrl = unsubscribeUrl,
                    preferencesUrl = preferencesUrl,
                    // The recipient's language setting localizes the render.
                    locale = recipientLocale,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("BML message email-channel render failed for message {} ({})", message.id, template.label, e)
                val attemptedTemplate = BmlMessageTemplateRender(
                    project = resolvedTemplate?.project ?: template.project,
                    templateKey = resolvedTemplate?.templateKey ?: template.templateKey,
                    version = resolvedTemplate?.version,
                    parameters = template.payload,
                )
                for (recipientId in included) {
                    deliveryTracking.recordEvent(
                        DeliveryEvent(
                            messageId = message.id,
                            recipientId = recipientId,
                            status = DeliveryStatusType.FAILED,
                            errorMessage = "BML message email-channel render failed: ${e.message}",
                        ),
                        attemptedTemplate,
                    )
                }
                throw e
            }
            val resolved = checkNotNull(resolvedTemplate)
            bmlTemplateRender = BmlMessageTemplateRender(
                project = resolved.project,
                templateKey = resolved.templateKey,
                version = rendered.version,
                parameters = template.payload,
            )
            Triple(
                rendered.subject,
                listOf(
                    mailer.newContent(ContentType.TEXT, rendered.text),
                    mailer.newContent(ContentType.HTML, rendered.html),
                ),
                rendered.images.map { InlineImage(cid = it.cid, mediaType = it.mediaType, filename = it.filename, contentBase64 = it.contentBase64) },
            )
        } ?: Triple(message.subject, message.content.map {
            val type = when (it.type) {
                MessageContentType.TEXT -> ContentType.TEXT
                MessageContentType.HTML -> ContentType.HTML
                else -> error("Unsupported content type: ${it.type}")
            }
            mailer.newContent(type, it.content)
        }, emptyList())
        val mailMessage = mailer.newMessage(
            from,
            to,
            subject,
            contents,
            inlineImages,
            mapOf(DeliveryTrackingArguments.MESSAGE_ID to message.id.toString()),
        )
        // Establish every recipient's aggregate before handing the message to the external
        // provider. Internal and provider events then update this same aggregate instead of
        // relying on a webhook to create the first delivery-status row.
        for (recipientId in included) {
            val event = DeliveryEvent(
                messageId = message.id,
                recipientId = recipientId,
                status = DeliveryStatusType.PENDING,
            )
            bmlTemplateRender?.let { deliveryTracking.recordEvent(event, it) }
                ?: deliveryTracking.recordEvent(event)
        }
        try {
            mailer.send(mailMessage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            for (recipientId in included) {
                deliveryTracking.recordEvent(DeliveryEvent(
                    messageId = message.id,
                    recipientId = recipientId,
                    status = DeliveryStatusType.FAILED,
                    errorMessage = e.message,
                ))
            }
            throw e
        }
        // The provider accepted the message. Persist SENT through a separate retryable job so
        // database failures cannot cause this message job to repeat the external handoff.
        withContext(NonCancellable) {
            val events = included.map { recipientId ->
                DeliveryEvent(
                    providerEventId = "bosca-${UUID.random()}",
                    messageId = message.id,
                    recipientId = recipientId,
                    status = DeliveryStatusType.SENT,
                )
            }
            try {
                RecordDeliveryEventsJob(events).enqueue(
                    messageQueue,
                    RecordDeliveryEventsJobExecutor::class,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(
                    "Email provider accepted message {}, but SENT tracking could not be queued",
                    message.id,
                    e,
                )
                throw FailException(
                    "Email provider accepted the message, but SENT tracking could not be queued",
                ).also { it.addSuppressed(e) }
            }
        }
    }

    private fun validate(message: Message) {
        if (message.channels.isEmpty()) error("Message must have at least one channel")
        if (message.recipients.isEmpty()) error("Message must have at least one recipient")
    }

    /**
     * Mint the unsubscribe/preferences URLs for a single-recipient BML render: one
     * stored token (scoped to the message's notification type when it is a known one,
     * otherwise unscoped) appended to the configured public pages. Minting failures degrade to
     * no links — the send must not fail over its footer.
     */
    private suspend fun mintPreferenceUrls(profileId: UUID, messageType: String?): Pair<String?, String?> {
        val unsubscribeBase = mailerConfiguration.unsubscribeUrl?.takeIf { it.isNotBlank() }
        val preferencesBase = mailerConfiguration.preferencesUrl?.takeIf { it.isNotBlank() }
        if (unsubscribeBase == null && preferencesBase == null) return null to null
        val token = try {
            val type = messageType?.takeIf { notificationTypes.get(it) != null }
            notificationPreferences.generateUnsubscribeToken(profileId, type)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("unsubscribe token minting failed for profile {}: {}", profileId, e.message)
            return null to null
        }
        fun withToken(base: String) = if ('?' in base) "$base&token=$token" else "$base?token=$token"
        return unsubscribeBase?.let(::withToken) to preferencesBase?.let(::withToken)
    }

    private suspend fun sendToPush(message: Message) {
        val highPriority = message.pushOptions?.priority.equals("HIGH", ignoreCase = true)
        val recipientIds = message.recipients.distinct()
        val profilesById = profileService.getAllByIds(recipientIds).associateBy { it.id }
        if (profilesById.isEmpty()) return

        val attributes = Batch<UUID, List<ProfileAttribute>>(profilesById.keys.toList())
        profileService.addAttributesToBatch(attributes)
        val profiles = buildList {
            for (recipientId in recipientIds) {
                val profile = profilesById[recipientId] ?: continue
                when (val decision = gate.evaluate(
                    profile.id,
                    DeliveryChannel.PUSH,
                    message.type,
                    highPriority = highPriority,
                )) {
                    GateDecision.Allow -> add(profile)
                    is GateDecision.Suppressed -> {
                        log.info("Suppressing push to {} for message {}: {}", profile.id, message.id, decision.reason)
                        recordGateEvent(
                            message.id,
                            profile.id,
                            DeliveryChannel.PUSH,
                            DeliveryStatusType.DROPPED,
                            decision.reason,
                        )
                    }
                    is GateDecision.Deferred -> {
                        log.info("Deferring push to {} for message {} until {}", profile.id, message.id, decision.until)
                        recordGateEvent(
                            message.id,
                            profile.id,
                            DeliveryChannel.PUSH,
                            DeliveryStatusType.DEFERRED,
                            GateReasons.QUIET_HOURS,
                        )
                        val delay = Duration.between(OffsetDateTime.now(), decision.until)
                        message.copy(recipients = listOf(profile.id))
                            .enqueueLater(messageQueue, MessageJob::class, timeout = delay.toKotlinDuration())
                    }
                }
            }
        }
        if (profiles.isEmpty()) return

        val principalIds = profiles.mapNotNull { it.principal }.distinct()
        if (principalIds.isEmpty()) return

        val deviceBatch = Batch<UUID, List<Device>>(principalIds)
        deviceService.addDevicesToBatch(deviceBatch)
        val devices = principalIds.flatMap { deviceBatch.getData(it).orEmpty() }
        if (devices.isEmpty()) return

        val tokenBatch = Batch<UUID, List<PushToken>>(devices.map { it.id }.distinct())
        deviceService.addPushTokensToBatch(tokenBatch)

        val bmlReference = message.bmlTemplate
        val resolvedBmlTemplate = bmlReference?.let { bmlMessageRegistry.resolve(it) }
        val notificationType = message.type?.let { notificationTypes.get(it) }

        for (profile in profiles) {
            val principalId = profile.principal ?: continue
            val tokensByRoute = mutableMapOf<Pair<PushPlatform, PushProvider>, MutableList<String>>()
            for (device in deviceBatch.getData(principalId).orEmpty()) {
                val platform = when (device.platform) {
                    PlatformType.ANDROID -> PushPlatform.ANDROID
                    PlatformType.IOS -> PushPlatform.IOS
                    PlatformType.WEB -> PushPlatform.WEB
                    PlatformType.DESKTOP -> PushPlatform.DESKTOP
                }
                for (token in tokenBatch.getData(device.id).orEmpty()) {
                    tokensByRoute
                        .getOrPut(platform to token.provider) { mutableListOf() }
                        .add(token.token)
                }
            }
            if (tokensByRoute.isEmpty()) continue

            val profileAttributes = attributes.getData(profile.id).orEmpty()
            val recipientName = profileAttributes.getAttributeString("bosca.profiles.name", "name") ?: profile.name
            val recipientEmail = profileAttributes.getAttributeString("bosca.profiles.email", "email")
            val locale = profileAttributes
                .getAttributeString("bosca.profiles.locale", "locale")
                ?.takeIf { it.isNotBlank() }
                ?.let(Locale::forLanguageTag)
            val renderedTemplate = resolvedBmlTemplate?.let { template ->
                checkNotNull(
                    bmlMessageRenderer.renderPush(
                        template = template,
                        payload = bmlReference.payload,
                        messageId = message.id.toString(),
                        recipientId = profile.id.toString(),
                        recipientName = recipientName,
                        recipientEmail = recipientEmail,
                        pushOptions = message.pushOptions,
                        locale = locale,
                    ),
                ) {
                    "BML message template ${template.project}/${template.templateKey} does not declare a push channel"
                }
            }
            val subject = renderedTemplate?.title ?: message.subject
            val content = renderedTemplate?.body
                ?: message.content.find { it.type == MessageContentType.TEXT }?.content.orEmpty()
            val pushOptions = renderedTemplate?.options
                .mergeDeliveryOverrides(message.pushOptions)
                .forNotificationType(notificationType)

            val bmlTemplateRender = resolvedBmlTemplate?.let { template ->
                BmlMessageTemplateRender(
                    project = template.project,
                    templateKey = template.templateKey,
                    version = template.version,
                    parameters = bmlReference?.payload,
                )
            }
            val pending = DeliveryEvent(
                messageId = message.id,
                recipientId = profile.id,
                channel = DeliveryChannel.PUSH,
                status = DeliveryStatusType.PENDING,
            )
            bmlTemplateRender?.let { deliveryTracking.recordEvent(pending, it) }
                ?: deliveryTracking.recordEvent(pending)

            var successCount = 0
            var failureCount = 0
            try {
                for ((route, tokens) in tokensByRoute) {
                    val (platform, provider) = route
                    val distinctTokens = tokens.distinct()
                    val result = sender.send(
                        distinctTokens,
                        subject,
                        content,
                        platform,
                        provider,
                        pushOptions,
                    )
                    successCount += result.successCount
                    failureCount += result.failureCount
                    if (result.invalidTokens.isNotEmpty()) {
                        try {
                            deviceService.removePushTokens(provider, result.invalidTokens.toList())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // The provider already accepted/rejected this send. Retrying the whole message
                            // would duplicate successful notifications, so surface cleanup failure in logs
                            // and let a later send retry removal of the same stale token.
                            log.error("Failed to remove invalid push tokens after message {}", message.id, e)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deliveryTracking.recordEvent(DeliveryEvent(
                    messageId = message.id,
                    recipientId = profile.id,
                    channel = DeliveryChannel.PUSH,
                    status = DeliveryStatusType.FAILED,
                    errorCode = "PUSH_PROVIDER_ERROR",
                    errorMessage = e.message,
                ))
                throw e
            }

            val resultEvent = DeliveryEvent(
                providerEventId = "bosca-${UUID.random()}",
                messageId = message.id,
                recipientId = profile.id,
                channel = DeliveryChannel.PUSH,
                status = if (successCount > 0) DeliveryStatusType.SENT else DeliveryStatusType.FAILED,
                errorCode = if (successCount == 0) "PUSH_NOT_SENT" else null,
                errorMessage = if (successCount == 0) {
                    "Push provider accepted no device tokens ($failureCount failed)"
                } else {
                    null
                },
            )
            if (successCount == 0) {
                deliveryTracking.recordEvent(resultEvent)
                continue
            }

            // At least one provider accepted the push. Persist SENT through a separate retryable
            // job so a database failure cannot cause the message job to repeat that external send.
            withContext(NonCancellable) {
                try {
                    RecordDeliveryEventsJob(listOf(resultEvent)).enqueue(
                        messageQueue,
                        RecordDeliveryEventsJobExecutor::class,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error(
                        "Push provider accepted message {}, but SENT tracking could not be queued",
                        message.id,
                        e,
                    )
                    throw FailException(
                        "Push provider accepted the message, but SENT tracking could not be queued",
                    ).also { it.addSuppressed(e) }
                }
            }
        }
    }

    private fun PushOptions?.forNotificationType(type: NotificationType?): PushOptions? {
        if (type == null) return this
        val options = this ?: PushOptions()
        return options.copy(
            androidChannelId = type.key,
            data = buildMap {
                options.data?.let(::putAll)
                put(NOTIFICATION_TYPE_DATA_KEY, type.key)
            },
        )
    }

    private fun PushOptions?.mergeDeliveryOverrides(overrides: PushOptions?): PushOptions? {
        if (this == null) return overrides
        if (overrides == null) return this
        val templateData = data
        val overrideData = overrides.data
        return PushOptions(
            imageUrl = imageUrl,
            defaultAction = defaultAction,
            actions = actions,
            priority = overrides.priority ?: priority,
            sound = overrides.sound ?: sound,
            badge = overrides.badge ?: badge,
            ttl = overrides.ttl ?: ttl,
            androidChannelId = overrides.androidChannelId ?: androidChannelId,
            androidTag = overrides.androidTag ?: androidTag,
            collapseKey = overrides.collapseKey ?: collapseKey,
            threadId = overrides.threadId ?: threadId,
            category = overrides.category ?: category,
            interruptionLevel = overrides.interruptionLevel ?: interruptionLevel,
            relevanceScore = overrides.relevanceScore ?: relevanceScore,
            mutableContent = overrides.mutableContent ?: mutableContent,
            contentAvailable = overrides.contentAvailable ?: contentAvailable,
            richContent = richContent,
            data = when {
                templateData == null -> overrideData
                overrideData == null -> templateData
                else -> templateData + overrideData
            },
        )
    }

    private suspend fun recordGateEvent(
        messageId: UUID,
        recipientId: UUID,
        channel: DeliveryChannel,
        status: DeliveryStatusType,
        reason: String,
    ) {
        deliveryTracking.recordEvent(DeliveryEvent(
            messageId = messageId,
            recipientId = recipientId,
            channel = channel,
            status = status,
            providerEvent = reason,
        ))
    }
}

/** Splits a logical message into one delivery per recipient profile and channel. */
internal fun Message.partitionForAsyncDelivery(): List<Message> {
    if (channels.isEmpty()) error("Message must have at least one channel")
    if (recipients.isEmpty()) error("Message must have at least one recipient")
    return channels.distinct().flatMap { channel ->
        recipients.distinct().map { recipientId ->
            copy(channels = listOf(channel), recipients = listOf(recipientId))
        }
    }
}
