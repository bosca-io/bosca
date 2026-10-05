@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.communications.service

import bosca.communications.jobs.RecordDeliveryEventsJob
import bosca.communications.mailers.Content
import bosca.communications.mailers.ContentType
import bosca.communications.mailers.Email
import bosca.communications.mailers.EmailMessage
import bosca.communications.mailers.InlineImage
import bosca.communications.mailers.Mailer
import bosca.communications.mailers.MailerConfiguration
import bosca.communications.mailers.MailerEmail
import bosca.communications.mailers.MailerType
import bosca.communications.model.DeliveryChannel
import bosca.communications.model.DeliveryEvent
import bosca.communications.model.DeliveryStatusType
import bosca.communications.model.BmlMessageTemplateRender
import bosca.communications.model.Message
import bosca.communications.model.MessageChannel
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.communications.model.MessageBmlTemplate
import bosca.communications.model.NotificationType
import bosca.communications.model.ResolvedMessageTemplate
import bosca.communications.model.PushAction
import bosca.communications.model.PushOptions
import bosca.communications.model.PushRichContent
import bosca.communications.model.RenderedPushTemplate
import bosca.communications.push.PushPlatform
import bosca.communications.push.PushSendResult
import bosca.communications.push.PushSender
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.devices.service.DeviceService
import bosca.devices.model.Device
import bosca.devices.model.PlatformType
import bosca.devices.model.PushToken
import bosca.devices.model.PushProvider
import bosca.graphql.Batch
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.serialization.UUID
import bosca.serialization.OffsetDateTime
import bosca.sharedqueue.jobs.FailException
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MessageServiceDeliveryTrackingTest {

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `provider acceptance queues status persistence separately`() = runBlocking {
        val fixture = fixture()
        val jobs = mutableListOf<Job>()
        coEvery { fixture.messageQueue.enqueue(any()) } coAnswers {
            jobs += firstArg<Job>()
            UUID.random()
        }

        val message = Message(
            channels = listOf(MessageChannel.EMAIL),
            subject = "Hello",
            recipients = listOf(fixture.recipientId),
            content = listOf(MessageContent(MessageContentType.TEXT, "Hello Ada")),
        )
        fixture.service.sendNow(message)

        assertEquals(1, fixture.mailer.sendCount)
        assertEquals(listOf(DeliveryStatusType.PENDING), fixture.recorded.map(DeliveryEvent::status))
        val statusJob = Json.decodeFromJsonElement(
            RecordDeliveryEventsJob.serializer(),
            jobs.single().getDefinition(),
        )
        assertEquals(1, statusJob.events.size)
        assertEquals(message.id, statusJob.events.single().messageId)
        assertEquals(fixture.recipientId, statusJob.events.single().recipientId)
        assertEquals(DeliveryStatusType.SENT, statusJob.events.single().status)
    }

    @Test
    fun `accepted send fails permanently when status persistence cannot be queued`() = runBlocking {
        val fixture = fixture(queueFails = true)

        val error = assertFailsWith<FailException> {
            fixture.service.sendNow(Message(
                channels = listOf(MessageChannel.EMAIL),
                subject = "Hello",
                recipients = listOf(fixture.recipientId),
                content = listOf(MessageContent(MessageContentType.TEXT, "Hello Ada")),
            ))
        }

        assertEquals(
            "Email provider accepted the message, but SENT tracking could not be queued",
            error.message,
        )
        assertEquals(1, fixture.mailer.sendCount)
        assertEquals(listOf(DeliveryStatusType.PENDING), fixture.recorded.map(DeliveryEvent::status))
    }

    @Test
    fun `provider rejection records FAILED and does not queue SENT`() = runBlocking {
        val fixture = fixture(mailerFails = true)
        val message = Message(
            channels = listOf(MessageChannel.EMAIL),
            subject = "Hello",
            recipients = listOf(fixture.recipientId),
            content = listOf(MessageContent(MessageContentType.TEXT, "Hello Ada")),
        )

        val error = assertFailsWith<IllegalStateException> {
            fixture.service.sendNow(message)
        }

        assertEquals("provider rejected message", error.message)
        assertEquals(1, fixture.mailer.sendCount)
        assertEquals(
            listOf(DeliveryStatusType.PENDING, DeliveryStatusType.FAILED),
            fixture.recorded.map(DeliveryEvent::status),
        )
        assertEquals("provider rejected message", fixture.recorded.last().errorMessage)
        coVerify(exactly = 0) { fixture.messageQueue.enqueue(any()) }
    }

    @Test
    fun `provider cancellation propagates without recording a false failure`() = runBlocking {
        val fixture = fixture(mailerCancellation = true)

        assertFailsWith<kotlinx.coroutines.CancellationException> {
            fixture.service.sendNow(Message(
                channels = listOf(MessageChannel.EMAIL),
                subject = "Hello",
                recipients = listOf(fixture.recipientId),
                content = listOf(MessageContent(MessageContentType.HTML, "<p>Hello Ada</p>")),
            ))
        }

        assertEquals(listOf(DeliveryStatusType.PENDING), fixture.recorded.map(DeliveryEvent::status))
        coVerify(exactly = 0) { fixture.messageQueue.enqueue(any()) }
    }

    @Test
    fun `email without an address is skipped and unsupported content fails before handoff`() = runBlocking {
        val missingEmail = fixture(includeEmail = false)
        missingEmail.service.sendNow(Message(
            channels = listOf(MessageChannel.EMAIL),
            subject = "Hello",
            recipients = listOf(missingEmail.recipientId),
            content = listOf(MessageContent(MessageContentType.TEXT, "Hello")),
        ))
        assertEquals(0, missingEmail.mailer.sendCount)
        assertTrue(missingEmail.recorded.isEmpty())

        val unsupported = fixture(gateDecision = GateDecision.Deferred(OffsetDateTime.now().plusMinutes(1)))
        val error = assertFailsWith<IllegalStateException> {
            unsupported.service.sendNow(Message(
                channels = listOf(MessageChannel.EMAIL),
                subject = "Hello",
                recipients = listOf(unsupported.recipientId),
                content = listOf(MessageContent(MessageContentType.IMAGE, "not email content")),
            ))
        }
        assertEquals("Unsupported content type: IMAGE", error.message)
        assertEquals(0, unsupported.mailer.sendCount)
        assertTrue(unsupported.recorded.isEmpty())
    }

    @Test
    fun `unsubscribe token failure does not block a templated email`() = runBlocking {
        val fixture = fixture(
            unsubscribeUrl = "https://example.com/unsubscribe?source=email",
            preferencesUrl = "https://example.com/preferences",
            tokenFails = true,
        )

        fixture.service.sendNow(Message(
            channels = listOf(MessageChannel.EMAIL),
            recipients = listOf(fixture.recipientId),
            bmlTemplate = MessageBmlTemplate("project", "template"),
        ))

        assertEquals(1, fixture.mailer.sendCount)
        assertEquals(listOf(DeliveryStatusType.PENDING), fixture.recorded.map(DeliveryEvent::status))
    }

    @Test
    fun `template resolution failure records the attempted template and remains retryable`() = runBlocking {
        val fixture = fixture(registryFails = true)
        val template = MessageBmlTemplate("unregistered-project", "missing-template")

        val failure = assertFailsWith<IllegalStateException> {
            fixture.service.sendNow(Message(
                channels = listOf(MessageChannel.EMAIL),
                recipients = listOf(fixture.recipientId),
                bmlTemplate = template,
            ))
        }

        assertEquals("template is not registered", failure.message)
        assertEquals(listOf(DeliveryStatusType.FAILED), fixture.recorded.map(DeliveryEvent::status))
        assertEquals(
            BmlMessageTemplateRender(
                project = template.project,
                templateKey = template.templateKey,
                version = null,
                parameters = template.payload,
            ),
            fixture.recordedTemplates.single(),
        )
        assertEquals(0, fixture.mailer.sendCount)
    }

    @Test
    fun `multi-recipient BML message renders and sends once per recipient locale`() = runBlocking {
        val fixture = fixture(
            recipientCount = 2,
            unsubscribeUrl = "https://example.com/unsubscribe?source=email",
            preferencesUrl = "https://example.com/preferences",
            locales = listOf("en", "es"),
        )

        fixture.service.sendNow(Message(
            channels = listOf(MessageChannel.EMAIL),
            recipients = fixture.recipientIds,
            bmlTemplate = MessageBmlTemplate(
                "project",
                "template",
                buildJsonObject { put("courseName", "Exploring Truth") },
            ),
        ))

        assertEquals(2, fixture.mailer.sendCount)
        assertEquals(listOf("en", "es"), fixture.renders.map { it.locale?.toLanguageTag() })
        assertEquals(
            listOf(DeliveryStatusType.PENDING, DeliveryStatusType.PENDING),
            fixture.recorded.map(DeliveryEvent::status),
        )
        assertEquals(2, fixture.recordedTemplates.size)
        assertTrue(fixture.recordedTemplates.all {
            it.project == "project" &&
                it.templateKey == "template" &&
                it.version == "20260804-v1" &&
                it.parameters == buildJsonObject { put("courseName", "Exploring Truth") }
        })
    }

    @Test
    fun `single-recipient templates receive scoped preference links`() = runBlocking {
        val fixture = fixture(
            unsubscribeUrl = "https://example.com/unsubscribe?source=email",
            preferencesUrl = "https://example.com/preferences",
            notificationTypeExists = true,
        )

        fixture.service.sendNow(Message(
            channels = listOf(MessageChannel.EMAIL),
            recipients = listOf(fixture.recipientId),
            bmlTemplate = MessageBmlTemplate("project", "template"),
            type = "marketing",
        ))

        val render = fixture.renders.single()
        assertEquals("https://example.com/unsubscribe?source=email&token=token", render.unsubscribeUrl)
        assertEquals("https://example.com/preferences?token=token", render.preferencesUrl)
        coVerify { fixture.notificationTypes.get("marketing") }
        coVerify { fixture.notificationPreferences.generateUnsubscribeToken(fixture.recipientId, "marketing") }
    }

    @Test
    fun `blank preference links skip token minting`() = runBlocking {
        val fixture = fixture(
            unsubscribeUrl = " ",
            preferencesUrl = "\t",
        )

        fixture.service.sendNow(Message(
            channels = listOf(MessageChannel.EMAIL),
            recipients = listOf(fixture.recipientId),
            bmlTemplate = MessageBmlTemplate("project", "template"),
        ))

        val render = fixture.renders.single()
        assertEquals(null, render.unsubscribeUrl)
        assertEquals(null, render.preferencesUrl)
        coVerify(exactly = 0) { fixture.notificationPreferences.generateUnsubscribeToken(any(), any()) }
    }

    @Test
    fun `an unknown notification type mints an unscoped token for the configured link`() = runBlocking {
        val fixture = fixture(
            unsubscribeUrl = "https://example.com/unsubscribe",
        )

        fixture.service.sendNow(Message(
            channels = listOf(MessageChannel.EMAIL),
            recipients = listOf(fixture.recipientId),
            bmlTemplate = MessageBmlTemplate("project", "template"),
            type = "unknown",
        ))

        val render = fixture.renders.single()
        assertEquals("https://example.com/unsubscribe?token=token", render.unsubscribeUrl)
        assertEquals(null, render.preferencesUrl)
        coVerify { fixture.notificationPreferences.generateUnsubscribeToken(fixture.recipientId, null) }
    }

    @Test
    fun `missing profile attribute batch data skips an email without failing`() = runBlocking {
        val fixture = fixture(attributeDataPresent = false)

        fixture.service.sendNow(Message(
            channels = listOf(MessageChannel.EMAIL),
            subject = "Hello",
            recipients = listOf(fixture.recipientId),
            content = listOf(MessageContent(MessageContentType.TEXT, "Hello")),
        ))

        assertEquals(0, fixture.mailer.sendCount)
        assertTrue(fixture.recorded.isEmpty())
    }

    @Test
    fun `direct email send rejects an oversized provider request before lookup`() = runBlocking {
        val fixture = fixture()
        val error = assertFailsWith<IllegalArgumentException> {
            fixture.service.sendNow(Message(
                channels = listOf(MessageChannel.EMAIL),
                subject = "Bulk",
                recipients = (1..1001).map { UUID.random() },
                content = listOf(MessageContent(MessageContentType.TEXT, "Hello")),
            ))
        }

        assertEquals("The configured mail provider supports at most 1000 recipients per message", error.message)
        assertEquals(0, fixture.mailer.sendCount)
    }

    @Test
    fun `messages require a channel and recipient`() = runBlocking {
        val fixture = fixture()

        assertEquals(
            "Message must have at least one channel",
            assertFailsWith<IllegalStateException> {
                fixture.service.sendNow(Message(
                    channels = emptyList(),
                    subject = "No channel",
                    recipients = listOf(fixture.recipientId),
                    content = emptyList(),
                ))
            }.message,
        )
        assertEquals(
            "Message must have at least one recipient",
            assertFailsWith<IllegalStateException> {
                fixture.service.sendNow(Message(
                    channels = listOf(MessageChannel.EMAIL),
                    subject = "No recipient",
                    recipients = emptyList(),
                    content = emptyList(),
                ))
            }.message,
        )
    }

    @Test
    fun `queued email messages are partitioned per profile before the provider boundary`() = runBlocking {
        val fixture = fixture()
        val jobs = mutableListOf<Job>()
        coEvery { fixture.messageQueue.enqueue(any()) } coAnswers {
            jobs += firstArg<Job>()
            UUID.random()
        }
        val message = Message(
            channels = listOf(MessageChannel.EMAIL),
            subject = "Bulk",
            recipients = (1..1001).map { UUID.random() },
            content = listOf(MessageContent(MessageContentType.TEXT, "Hello")),
        )

        fixture.service.send(message)

        val messages = jobs.map {
            Json.decodeFromJsonElement(Message.serializer(), it.getDefinition())
        }
        assertEquals(1001, messages.size)
        assertTrue(messages.all { it.recipients.size == 1 })
        assertTrue(messages.all { it.id == message.id })
    }

    @Test
    fun `queued messages isolate each delivery channel in its own job`() = runBlocking {
        val fixture = fixture()
        val jobs = mutableListOf<Job>()
        coEvery { fixture.messageQueue.enqueue(any()) } coAnswers {
            jobs += firstArg<Job>()
            UUID.random()
        }
        val message = Message(
            channels = listOf(MessageChannel.EMAIL, MessageChannel.PUSH),
            subject = "Multi-channel",
            recipients = listOf(UUID.random()),
            content = listOf(MessageContent(MessageContentType.TEXT, "Hello")),
        )

        fixture.service.send(message)

        val messages = jobs.map {
            Json.decodeFromJsonElement(Message.serializer(), it.getDefinition())
        }
        assertEquals(
            listOf(listOf(MessageChannel.EMAIL), listOf(MessageChannel.PUSH)),
            messages.map(Message::channels),
        )
    }

    @Test
    fun `push delivery gates recipients groups tokens by platform and defers quiet-hour recipients`() = runBlocking {
        val allowed = PlatformType.entries.associateWith { platform ->
            Profile(
                id = UUID.random(),
                principal = UUID.random(),
                type = ProfileType.GENERIC,
                name = platform.name,
                visibility = ProfileVisibility.USER,
            )
        }
        val noPrincipal = profile("No Principal")
        val suppressed = profile("Suppressed")
        val deferredOne = profile("Deferred One")
        val deferredTwo = profile("Deferred Two")
        val deferredUntil = OffsetDateTime.now().plusMinutes(5)
        val profiles = allowed.values + noPrincipal + suppressed + deferredOne + deferredTwo
        val decisions = mapOf(
            suppressed.id to GateDecision.Suppressed("opted out"),
            deferredOne.id to GateDecision.Deferred(deferredUntil),
            deferredTwo.id to GateDecision.Deferred(deferredUntil),
        )
        val devices = allowed.map { (platform, profile) ->
            val principal = requireNotNull(profile.principal)
            principal to listOf(
                Device(
                    id = UUID.random(),
                    principalId = principal,
                    platform = platform,
                ),
                Device(
                    id = UUID.random(),
                    principalId = principal,
                    platform = platform,
                ),
            )
        }.toMap()
        val tokens = devices.values.flatten().mapIndexed { index, device ->
            device.id to if (index % 2 == 0) {
                listOf(PushToken(deviceId = device.id, token = "token-${device.platform.name.lowercase()}"))
            } else {
                emptyList()
            }
        }.toMap()
        val fixture = pushFixture(profiles, decisions, devices, tokens)
        val message = Message(
            channels = listOf(MessageChannel.PUSH),
            subject = "Update",
            recipients = profiles.map(Profile::id),
            content = listOf(MessageContent(MessageContentType.TEXT, "A new update")),
            pushOptions = PushOptions(priority = "HIGH"),
            type = "marketing",
        )

        fixture.service.sendNow(message)

        assertEquals(PlatformType.entries.map { PushPlatform.valueOf(it.name) }.toSet(), fixture.sent.map { it.platform }.toSet())
        assertEquals(PlatformType.entries.size, fixture.sent.size)
        assertTrue(fixture.sent.all { it.tokens.size == 1 && it.content == "A new update" })
        assertTrue(fixture.sent.all { it.provider == PushProvider.FCM })
        assertTrue(fixture.sent.all { it.options?.androidChannelId == "marketing" })
        assertTrue(fixture.sent.all { it.options?.data?.get("notification_type") == "marketing" })
        assertEquals(
            listOf(
                DeliveryStatusType.DROPPED,
                DeliveryStatusType.DEFERRED,
                DeliveryStatusType.DEFERRED,
                DeliveryStatusType.PENDING,
                DeliveryStatusType.PENDING,
                DeliveryStatusType.PENDING,
                DeliveryStatusType.PENDING,
            ),
            fixture.recorded.map(DeliveryEvent::status),
        )
        val deferredMessages = fixture.delayed.map {
            Json.decodeFromJsonElement(Message.serializer(), it.getDefinition())
        }
        assertEquals(
            setOf(listOf(deferredOne.id), listOf(deferredTwo.id)),
            deferredMessages.map(Message::recipients).toSet(),
        )
        assertTrue(deferredMessages.all { it.channels == listOf(MessageChannel.PUSH) })
        coVerify {
            fixture.gate.evaluate(any(), DeliveryChannel.PUSH, "marketing", null, true)
        }
    }

    @Test
    fun `push delivery exits cleanly without principals devices or tokens`() = runBlocking {
        val noPrincipal = profile("No Principal")
        pushFixture(listOf(noPrincipal)).service.sendNow(pushMessage(noPrincipal.id))

        val withPrincipal = profile("No Devices", principal = UUID.random())
        pushFixture(listOf(withPrincipal)).service.sendNow(pushMessage(withPrincipal.id))

        val withDevice = profile("No Tokens", principal = UUID.random())
        val principal = requireNotNull(withDevice.principal)
        val device = Device(
            id = UUID.random(),
            principalId = principal,
            platform = PlatformType.ANDROID,
        )
        val fixture = pushFixture(
            listOf(withDevice),
            devices = mapOf(principal to listOf(device)),
        )
        fixture.service.sendNow(pushMessage(withDevice.id, content = emptyList()))

        assertTrue(fixture.sent.isEmpty())
        assertTrue(fixture.recorded.isEmpty())
    }

    @Test
    fun `push delivery uses an empty body when there is no text content`() = runBlocking {
        val profile = profile("Image Push", principal = UUID.random())
        val principal = requireNotNull(profile.principal)
        val device = Device(
            id = UUID.random(),
            principalId = principal,
            platform = PlatformType.ANDROID,
        )
        val fixture = pushFixture(
            profiles = listOf(profile),
            devices = mapOf(principal to listOf(device)),
            tokens = mapOf(device.id to listOf(PushToken(deviceId = device.id, token = "android-token"))),
        )

        fixture.service.sendNow(pushMessage(
            profile.id,
            content = listOf(MessageContent(MessageContentType.IMAGE, "ignored")),
        ))

        assertEquals("", fixture.sent.single().content)
    }

    @Test
    fun `push delivery derives notification metadata without producer options`() = runBlocking {
        val profile = profile("Push Recipient", principal = UUID.random())
        val principal = requireNotNull(profile.principal)
        val device = Device(UUID.random(), principalId = null, platform = PlatformType.ANDROID)
        val fixture = pushFixture(
            profiles = listOf(profile),
            devices = mapOf(principal to listOf(device)),
            tokens = mapOf(device.id to listOf(PushToken(deviceId = device.id, token = "android-token"))),
            locales = mapOf(profile.id to " "),
        )

        fixture.service.sendNow(Message(
            channels = listOf(MessageChannel.PUSH),
            subject = "New message",
            recipients = listOf(profile.id),
            content = listOf(MessageContent(MessageContentType.TEXT, "Open chat")),
            type = "chat-message",
        ))

        assertEquals(
            PushOptions(
                androidChannelId = "chat-message",
                data = mapOf("notification_type" to "chat-message"),
            ),
            fixture.sent.single().options,
        )
    }

    @Test
    fun `push delivery removes tokens permanently rejected by the provider`() = runBlocking {
        val profile = profile("Push Recipient", principal = UUID.random())
        val principal = requireNotNull(profile.principal)
        val device = Device(
            id = UUID.random(),
            principalId = principal,
            platform = PlatformType.ANDROID,
        )
        val fixture = pushFixture(
            profiles = listOf(profile),
            devices = mapOf(principal to listOf(device)),
            tokens = mapOf(device.id to listOf(
                PushToken(deviceId = device.id, token = "active-token"),
                PushToken(deviceId = device.id, token = "expired-token"),
            )),
            invalidTokens = setOf("expired-token"),
        )

        fixture.service.sendNow(pushMessage(profile.id))

        assertEquals(listOf(PushProvider.FCM to listOf("expired-token")), fixture.removedTokens)
        assertEquals(listOf("active-token", "expired-token"), fixture.sent.single().tokens)
    }

    @Test
    fun `push provider acceptance creates a PUSH status and queues SENT persistence`() = runBlocking {
        val profile = profile("Push Recipient", principal = UUID.random())
        val principal = requireNotNull(profile.principal)
        val device = Device(UUID.random(), principal, PlatformType.ANDROID)
        val fixture = pushFixture(
            profiles = listOf(profile),
            devices = mapOf(principal to listOf(device)),
            tokens = mapOf(device.id to listOf(PushToken(deviceId = device.id, token = "android-token"))),
        )
        val message = pushMessage(profile.id)

        fixture.service.sendNow(message)

        assertEquals(listOf(DeliveryStatusType.PENDING), fixture.recorded.map(DeliveryEvent::status))
        assertEquals(DeliveryChannel.PUSH, fixture.recorded.single().channel)
        val statusJob = Json.decodeFromJsonElement(
            RecordDeliveryEventsJob.serializer(),
            fixture.queued.single().getDefinition(),
        )
        assertEquals(1, statusJob.events.size)
        assertEquals(message.id, statusJob.events.single().messageId)
        assertEquals(profile.id, statusJob.events.single().recipientId)
        assertEquals(DeliveryChannel.PUSH, statusJob.events.single().channel)
        assertEquals(DeliveryStatusType.SENT, statusJob.events.single().status)
    }

    @Test
    fun `push provider rejection records FAILED without queuing SENT`() = runBlocking {
        val profile = profile("Push Recipient", principal = UUID.random())
        val principal = requireNotNull(profile.principal)
        val device = Device(UUID.random(), principal, PlatformType.ANDROID)
        val fixture = pushFixture(
            profiles = listOf(profile),
            devices = mapOf(principal to listOf(device)),
            tokens = mapOf(device.id to listOf(PushToken(deviceId = device.id, token = "rejected-token"))),
            pushResult = PushSendResult(failureCount = 1),
        )

        fixture.service.sendNow(pushMessage(profile.id))

        assertEquals(
            listOf(DeliveryStatusType.PENDING, DeliveryStatusType.FAILED),
            fixture.recorded.map(DeliveryEvent::status),
        )
        assertEquals(DeliveryChannel.PUSH, fixture.recorded.last().channel)
        assertEquals("PUSH_NOT_SENT", fixture.recorded.last().errorCode)
        assertEquals("Push provider accepted no device tokens (1 failed)", fixture.recorded.last().errorMessage)
        assertTrue(fixture.queued.isEmpty())
    }

    @Test
    fun `push provider error records FAILED and remains retryable`() = runBlocking {
        val profile = profile("Push Recipient", principal = UUID.random())
        val principal = requireNotNull(profile.principal)
        val device = Device(UUID.random(), principal, PlatformType.ANDROID)
        val fixture = pushFixture(
            profiles = listOf(profile),
            devices = mapOf(principal to listOf(device)),
            tokens = mapOf(device.id to listOf(PushToken(deviceId = device.id, token = "android-token"))),
            pushFailure = IllegalStateException("provider unavailable"),
        )

        val error = assertFailsWith<IllegalStateException> {
            fixture.service.sendNow(pushMessage(profile.id))
        }

        assertEquals("provider unavailable", error.message)
        assertEquals(
            listOf(DeliveryStatusType.PENDING, DeliveryStatusType.FAILED),
            fixture.recorded.map(DeliveryEvent::status),
        )
        assertEquals("PUSH_PROVIDER_ERROR", fixture.recorded.last().errorCode)
        assertEquals("provider unavailable", fixture.recorded.last().errorMessage)
        assertTrue(fixture.queued.isEmpty())
    }

    @Test
    fun `push provider cancellation does not record a false failure`() = runBlocking {
        val profile = profile("Push Recipient", principal = UUID.random())
        val principal = requireNotNull(profile.principal)
        val device = Device(UUID.random(), principal, PlatformType.ANDROID)
        val fixture = pushFixture(
            profiles = listOf(profile),
            devices = mapOf(principal to listOf(device)),
            tokens = mapOf(device.id to listOf(PushToken(deviceId = device.id, token = "android-token"))),
            pushFailure = kotlinx.coroutines.CancellationException("cancelled"),
        )

        assertFailsWith<kotlinx.coroutines.CancellationException> {
            fixture.service.sendNow(pushMessage(profile.id))
        }

        assertEquals(listOf(DeliveryStatusType.PENDING), fixture.recorded.map(DeliveryEvent::status))
        assertTrue(fixture.queued.isEmpty())
    }

    @Test
    fun `accepted push fails permanently when status persistence cannot be queued`() = runBlocking {
        val profile = profile("Push Recipient", principal = UUID.random())
        val principal = requireNotNull(profile.principal)
        val device = Device(UUID.random(), principal, PlatformType.ANDROID)
        val fixture = pushFixture(
            profiles = listOf(profile),
            devices = mapOf(principal to listOf(device)),
            tokens = mapOf(device.id to listOf(PushToken(deviceId = device.id, token = "android-token"))),
            statusQueueFails = true,
        )

        val error = assertFailsWith<FailException> {
            fixture.service.sendNow(pushMessage(profile.id))
        }

        assertEquals(
            "Push provider accepted the message, but SENT tracking could not be queued",
            error.message,
        )
        assertEquals(1, fixture.sent.size)
        assertEquals(listOf(DeliveryStatusType.PENDING), fixture.recorded.map(DeliveryEvent::status))
    }

    @Test
    fun `accepted push propagates cancellation while queuing status persistence`() = runBlocking {
        val profile = profile("Push Recipient", principal = UUID.random())
        val principal = requireNotNull(profile.principal)
        val device = Device(UUID.random(), principal, PlatformType.ANDROID)
        val fixture = pushFixture(
            profiles = listOf(profile),
            devices = mapOf(principal to listOf(device)),
            tokens = mapOf(device.id to listOf(PushToken(deviceId = device.id, token = "android-token"))),
            statusQueueCancellation = true,
        )

        assertFailsWith<kotlinx.coroutines.CancellationException> {
            fixture.service.sendNow(pushMessage(profile.id))
        }

        assertEquals(1, fixture.sent.size)
        assertEquals(listOf(DeliveryStatusType.PENDING), fixture.recorded.map(DeliveryEvent::status))
    }

    @Test
    fun `push delivery does not repeat a provider send when invalid token cleanup fails`() = runBlocking {
        val profile = profile("Push Recipient", principal = UUID.random())
        val principal = requireNotNull(profile.principal)
        val device = Device(
            id = UUID.random(),
            principalId = principal,
            platform = PlatformType.ANDROID,
        )
        val fixture = pushFixture(
            profiles = listOf(profile),
            devices = mapOf(principal to listOf(device)),
            tokens = mapOf(device.id to listOf(
                PushToken(deviceId = device.id, token = "expired-token"),
            )),
            invalidTokens = setOf("expired-token"),
            tokenRemovalFails = true,
        )

        fixture.service.sendNow(pushMessage(profile.id))

        assertEquals(1, fixture.sent.size)
        assertTrue(fixture.removedTokens.isEmpty())
    }

    @Test
    fun `push delivery keeps FCM and APNs tokens in provider-specific batches`() = runBlocking {
        val profile = profile("iOS Recipient", principal = UUID.random())
        val principal = requireNotNull(profile.principal)
        val device = Device(
            id = UUID.random(),
            principalId = principal,
            platform = PlatformType.IOS,
        )
        val fixture = pushFixture(
            profiles = listOf(profile),
            devices = mapOf(principal to listOf(device)),
            tokens = mapOf(device.id to listOf(
                PushToken(deviceId = device.id, token = "fcm-token", provider = PushProvider.FCM),
                PushToken(deviceId = device.id, token = "apns-token", provider = PushProvider.APNS),
            )),
        )

        fixture.service.sendNow(pushMessage(profile.id))

        assertEquals(2, fixture.sent.size)
        assertEquals(
            setOf(PushProvider.FCM to listOf("fcm-token"), PushProvider.APNS to listOf("apns-token")),
            fixture.sent.map { it.provider to it.tokens }.toSet(),
        )
    }

    @Test
    fun `push delivery renders the push region from a BML message unit`() = runBlocking {
        val profile = profile("Push Recipient", principal = UUID.random())
        val principal = requireNotNull(profile.principal)
        val device = Device(UUID.random(), principal, PlatformType.ANDROID)
        val fixture = pushFixture(
            profiles = listOf(profile),
            devices = mapOf(principal to listOf(device)),
            tokens = mapOf(device.id to listOf(PushToken(deviceId = device.id, token = "android-token"))),
            bmlPushTemplate = RenderedPushTemplate(
                title = "Ada in Planning",
                body = "Project update",
                options = PushOptions(
                    defaultAction = PushAction("open-chat", "Open chat", "https://example.com/chat/1"),
                    threadId = "chat-1",
                ),
            ),
        )
        val payload = buildJsonObject { put("channelId", "1") }

        fixture.service.sendNow(Message(
            channels = listOf(MessageChannel.PUSH),
            recipients = listOf(profile.id),
            bmlTemplate = MessageBmlTemplate("bosca-messages", "chat-message", payload),
        ))

        val sent = fixture.sent.single()
        assertEquals("Ada in Planning", sent.subject)
        assertEquals("Project update", sent.content)
        assertEquals("https://example.com/chat/1", sent.options?.defaultAction?.url)
    }

    @Test
    fun `BML push presentation wins while producer delivery overrides and data are retained`() = runBlocking {
        val profile = profile("Push Recipient", principal = UUID.random())
        val principal = requireNotNull(profile.principal)
        val device = Device(UUID.random(), principal, PlatformType.ANDROID)
        val templateOptions = PushOptions(
            imageUrl = "https://example.com/template.png",
            defaultAction = PushAction("template-action", url = "https://example.com/template"),
            actions = listOf(PushAction("template-secondary")),
            priority = "NORMAL",
            sound = "template.aiff",
            badge = 1,
            ttl = 60,
            androidChannelId = "template-channel",
            androidTag = "template-tag",
            collapseKey = "template-collapse",
            threadId = "template-thread",
            category = "TEMPLATE",
            interruptionLevel = "active",
            relevanceScore = 0.25,
            mutableContent = false,
            contentAvailable = false,
            data = mapOf("template" to "yes", "shared" to "template"),
            richContent = PushRichContent(),
        )
        val fixture = pushFixture(
            profiles = listOf(profile),
            devices = mapOf(principal to listOf(device)),
            tokens = mapOf(device.id to listOf(PushToken(deviceId = device.id, token = "android-token"))),
            bmlPushTemplate = RenderedPushTemplate("Title", "Body", templateOptions),
        )
        val overrides = PushOptions(
            imageUrl = "https://example.com/producer.png",
            defaultAction = PushAction("producer-action"),
            actions = listOf(PushAction("producer-secondary")),
            priority = "HIGH",
            sound = "default",
            badge = 2,
            ttl = 120,
            androidChannelId = "delivery-channel",
            androidTag = "delivery-tag",
            collapseKey = "delivery-collapse",
            threadId = "delivery-thread",
            category = "DELIVERY",
            interruptionLevel = "time-sensitive",
            relevanceScore = 0.75,
            mutableContent = true,
            contentAvailable = true,
            data = mapOf("override" to "yes", "shared" to "override"),
            richContent = PushRichContent(),
        )

        fixture.service.sendNow(Message(
            channels = listOf(MessageChannel.PUSH),
            recipients = listOf(profile.id),
            pushOptions = overrides,
            bmlTemplate = MessageBmlTemplate("bosca-messages", "chat-message"),
        ))

        assertEquals(
            templateOptions.copy(
                priority = overrides.priority,
                sound = overrides.sound,
                badge = overrides.badge,
                ttl = overrides.ttl,
                androidChannelId = overrides.androidChannelId,
                androidTag = overrides.androidTag,
                collapseKey = overrides.collapseKey,
                threadId = overrides.threadId,
                category = overrides.category,
                interruptionLevel = overrides.interruptionLevel,
                relevanceScore = overrides.relevanceScore,
                mutableContent = overrides.mutableContent,
                contentAvailable = overrides.contentAvailable,
                data = mapOf("template" to "yes", "shared" to "override", "override" to "yes"),
            ),
            fixture.sent.single().options,
        )

        val fallbackFixture = pushFixture(
            profiles = listOf(profile),
            devices = mapOf(principal to listOf(device)),
            tokens = mapOf(device.id to listOf(PushToken(deviceId = device.id, token = "fallback-token"))),
            bmlPushTemplate = RenderedPushTemplate("Title", "Body", templateOptions),
        )
        fallbackFixture.service.sendNow(Message(
            channels = listOf(MessageChannel.PUSH),
            recipients = listOf(profile.id),
            pushOptions = PushOptions(),
            bmlTemplate = MessageBmlTemplate("bosca-messages", "chat-message"),
        ))
        assertEquals(templateOptions, fallbackFixture.sent.single().options)

        val producerData = mapOf("producer" to "yes")
        val producerDataFixture = pushFixture(
            profiles = listOf(profile),
            devices = mapOf(principal to listOf(device)),
            tokens = mapOf(device.id to listOf(PushToken(deviceId = device.id, token = "producer-token"))),
            bmlPushTemplate = RenderedPushTemplate("Title", "Body", PushOptions()),
        )
        producerDataFixture.service.sendNow(Message(
            channels = listOf(MessageChannel.PUSH),
            recipients = listOf(profile.id),
            pushOptions = PushOptions(data = producerData),
            bmlTemplate = MessageBmlTemplate("bosca-messages", "chat-message"),
        ))
        assertEquals(producerData, producerDataFixture.sent.single().options?.data)
    }

    @Test
    fun `BML push delivery renders once per recipient locale`() = runBlocking {
        val english = profile("English Recipient", principal = UUID.random())
        val spanish = profile("Spanish Recipient", principal = UUID.random())
        val englishDevice = Device(UUID.random(), english.principal, PlatformType.ANDROID)
        val spanishDevice = Device(UUID.random(), spanish.principal, PlatformType.ANDROID)
        val fixture = pushFixture(
            profiles = listOf(english, spanish),
            devices = mapOf(
                requireNotNull(english.principal) to listOf(englishDevice),
                requireNotNull(spanish.principal) to listOf(spanishDevice),
            ),
            tokens = mapOf(
                englishDevice.id to listOf(PushToken(deviceId = englishDevice.id, token = "token-en")),
                spanishDevice.id to listOf(PushToken(deviceId = spanishDevice.id, token = "token-es")),
            ),
            locales = mapOf(english.id to "en", spanish.id to "es"),
            bmlPushTemplate = RenderedPushTemplate("Fallback", "Fallback"),
            bmlPushTemplatesByLocale = mapOf(
                "en" to RenderedPushTemplate("Project update", "Open chat"),
                "es" to RenderedPushTemplate("Actualización del proyecto", "Abrir chat"),
            ),
        )

        fixture.service.sendNow(Message(
            channels = listOf(MessageChannel.PUSH),
            recipients = listOf(english.id, spanish.id),
            bmlTemplate = MessageBmlTemplate("bosca-messages", "chat-message"),
        ))

        assertEquals(2, fixture.sent.size)
        assertEquals("Project update", fixture.sent.single { it.tokens == listOf("token-en") }.subject)
        assertEquals("Actualización del proyecto", fixture.sent.single { it.tokens == listOf("token-es") }.subject)
        assertEquals(
            listOf<String?>(english.id.toString(), spanish.id.toString()),
            fixture.renderedProfileIds,
        )
        assertEquals(listOf<String?>("English Recipient", "Spanish Recipient"), fixture.renderedNames)
    }

    @Test
    fun `BML push delivery does not render when no recipient has a provider token`() = runBlocking {
        val profile = profile("Push Recipient", principal = UUID.random())
        val fixture = pushFixture(
            profiles = listOf(profile),
            bmlPushTemplate = RenderedPushTemplate("Unused", "Unused"),
        )

        fixture.service.sendNow(Message(
            channels = listOf(MessageChannel.PUSH),
            recipients = listOf(profile.id),
            bmlTemplate = MessageBmlTemplate("bosca-messages", "chat-message"),
        ))

        assertTrue(fixture.renderedLocales.isEmpty())
        assertTrue(fixture.sent.isEmpty())
    }

    @Test
    fun `BML push delivery fails when the message unit has no push channel`() = runBlocking {
        val profile = profile("Push Recipient", principal = UUID.random())
        val principal = requireNotNull(profile.principal)
        val device = Device(UUID.random(), principal, PlatformType.ANDROID)
        val fixture = pushFixture(
            profiles = listOf(profile),
            devices = mapOf(principal to listOf(device)),
            tokens = mapOf(device.id to listOf(PushToken(deviceId = device.id, token = "android-token"))),
            bmlPushTemplate = null,
        )

        val failure = assertFailsWith<IllegalStateException> {
            fixture.service.sendNow(Message(
                channels = listOf(MessageChannel.PUSH),
                recipients = listOf(profile.id),
                bmlTemplate = MessageBmlTemplate("bosca-messages", "email-only"),
            ))
        }

        assertEquals(
            "BML message template bosca-messages/chat-message does not declare a push channel",
            failure.message,
        )
        assertTrue(fixture.sent.isEmpty())
    }

    private fun profile(
        name: String,
        principal: UUID? = null,
    ) = Profile(
        id = UUID.random(),
        principal = principal,
        type = ProfileType.GENERIC,
        name = name,
        visibility = ProfileVisibility.USER,
    )

    private fun pushMessage(
        recipientId: UUID,
        content: List<MessageContent> = listOf(MessageContent(MessageContentType.TEXT, "Push")),
    ) = Message(
        channels = listOf(MessageChannel.PUSH),
        subject = "Push",
        recipients = listOf(recipientId),
        content = content,
    )

    private fun pushFixture(
        profiles: List<Profile>,
        decisions: Map<UUID, GateDecision> = emptyMap(),
        devices: Map<UUID, List<Device>> = emptyMap(),
        tokens: Map<UUID, List<PushToken>> = emptyMap(),
        invalidTokens: Set<String> = emptySet(),
        pushResult: PushSendResult? = null,
        pushFailure: Throwable? = null,
        statusQueueFails: Boolean = false,
        statusQueueCancellation: Boolean = false,
        tokenRemovalFails: Boolean = false,
        bmlPushTemplate: RenderedPushTemplate? = null,
        locales: Map<UUID, String> = emptyMap(),
        bmlPushTemplatesByLocale: Map<String, RenderedPushTemplate> = emptyMap(),
    ): PushFixture {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { Json }
        val profileService = mockk<ProfileService>()
        val deviceService = mockk<DeviceService>()
        val gate = mockk<NotificationPreferenceGate>()
        val tracking = mockk<DeliveryTrackingService>()
        val queue = mockk<JobQueue>(relaxed = true)
        val sender = mockk<PushSender>()
        val notificationTypes = mockk<NotificationTypeService>()
        val bmlRenderer = mockk<BmlMessageTemplateRendererService>()
        val bmlRegistry = mockk<BmlMessageRegistryService>()
        val recorded = mutableListOf<DeliveryEvent>()
        val delayed = mutableListOf<Job>()
        val queued = mutableListOf<Job>()
        val sent = mutableListOf<PushCall>()
        val removedTokens = mutableListOf<Pair<PushProvider, List<String>>>()
        val renderedLocales = mutableListOf<String?>()
        val renderedProfileIds = mutableListOf<String?>()
        val renderedNames = mutableListOf<String?>()

        coEvery { bmlRegistry.resolve(any()) } returns ResolvedMessageTemplate("bosca-messages", "chat-message")
        coEvery {
            bmlRenderer.renderPush(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            renderedProfileIds += arg<String?>(3)
            renderedNames += arg<String?>(4)
            val locale = arg<Locale?>(7)?.toLanguageTag()
            renderedLocales += locale
            bmlPushTemplatesByLocale[locale] ?: bmlPushTemplate
        }

        coEvery { profileService.getAllByIds(any()) } returns profiles
        coEvery { profileService.addAttributesToBatch(any()) } coAnswers {
            val batch = firstArg<Batch<UUID, List<ProfileAttribute>>>()
            for ((profileId, locale) in locales) {
                batch.setData(profileId, listOf(ProfileAttribute(
                    profile = profileId,
                    typeId = "bosca.profiles.locale",
                    visibility = ProfileVisibility.USER,
                    confidence = 100,
                    priority = 0,
                    source = "test",
                    attributes = buildJsonObject { put("locale", locale) },
                )))
            }
        }
        coEvery { gate.evaluate(any(), DeliveryChannel.PUSH, any(), null, any()) } coAnswers {
            decisions[firstArg<UUID>()] ?: GateDecision.Allow
        }
        coEvery { notificationTypes.get(any()) } answers {
            NotificationType(key = firstArg(), name = "Notification")
        }
        coEvery { deviceService.addDevicesToBatch(any()) } coAnswers {
            val batch = firstArg<Batch<UUID, List<Device>>>()
            for ((id, values) in devices) {
                batch.setData(id, values)
            }
        }
        coEvery { deviceService.addPushTokensToBatch(any()) } coAnswers {
            val batch = firstArg<Batch<UUID, List<PushToken>>>()
            for ((id, values) in tokens) {
                batch.setData(id, values)
            }
        }
        coEvery { deviceService.removePushTokens(any()) } coAnswers {
            removedTokens += PushProvider.FCM to firstArg<List<String>>()
        }
        coEvery { deviceService.removePushTokens(any<PushProvider>(), any()) } coAnswers {
            if (tokenRemovalFails) {
                throw IllegalStateException("token cleanup unavailable")
            }
            removedTokens += firstArg<PushProvider>() to secondArg<List<String>>()
        }
        coEvery { tracking.recordEvent(any()) } coAnswers {
            recorded += firstArg<DeliveryEvent>()
        }
        coEvery { tracking.recordEvent(any(), any()) } coAnswers {
            recorded += firstArg<DeliveryEvent>()
        }
        coEvery { queue.enqueueLater(any(), any()) } coAnswers {
            delayed += firstArg<Job>()
            UUID.random()
        }
        if (statusQueueCancellation) {
            coEvery { queue.enqueue(any()) } throws kotlinx.coroutines.CancellationException("cancelled")
        } else if (statusQueueFails) {
            coEvery { queue.enqueue(any()) } throws IllegalStateException("queue unavailable")
        } else {
            coEvery { queue.enqueue(any()) } coAnswers {
                queued += firstArg<Job>()
                UUID.random()
            }
        }
        coEvery { sender.send(any(), any(), any(), any(), any<PushProvider>(), any()) } coAnswers {
            pushFailure?.let { throw it }
            sent += PushCall(
                tokens = firstArg(),
                subject = secondArg(),
                content = arg(2),
                platform = arg(3),
                provider = arg(4),
                options = arg(5),
            )
            pushResult ?: PushSendResult(
                successCount = firstArg<List<String>>().size - invalidTokens.size,
                failureCount = invalidTokens.size,
                invalidTokens = invalidTokens,
            )
        }

        return PushFixture(
            gate = gate,
            recorded = recorded,
            delayed = delayed,
            queued = queued,
            sent = sent,
            removedTokens = removedTokens,
            renderedLocales = renderedLocales,
            renderedProfileIds = renderedProfileIds,
            renderedNames = renderedNames,
            service = MessageServiceImpl(
                profileService = profileService,
                deviceService = deviceService,
                messageQueue = queue,
                mailerConfiguration = MailerConfiguration(
                    type = MailerType.SENDGRID,
                    from = MailerEmail("Bosca", "noreply@example.com"),
                ),
                mailer = RecordingMailer(),
                sender = sender,
                deliveryTracking = tracking,
                gate = gate,
                bmlMessageRenderer = bmlRenderer,
                bmlMessageRegistry = bmlRegistry,
                notificationPreferences = mockk(),
                notificationTypes = notificationTypes,
            ),
        )
    }

    private data class PushFixture(
        val gate: NotificationPreferenceGate,
        val recorded: MutableList<DeliveryEvent>,
        val delayed: MutableList<Job>,
        val queued: MutableList<Job>,
        val sent: MutableList<PushCall>,
        val removedTokens: MutableList<Pair<PushProvider, List<String>>>,
        val renderedLocales: MutableList<String?>,
        val renderedProfileIds: MutableList<String?>,
        val renderedNames: MutableList<String?>,
        val service: MessageServiceImpl,
    )

    private data class PushCall(
        val tokens: List<String>,
        val subject: String,
        val content: String,
        val platform: PushPlatform,
        val provider: PushProvider,
        val options: PushOptions?,
    )

    private fun fixture(
        queueFails: Boolean = false,
        mailerFails: Boolean = false,
        mailerCancellation: Boolean = false,
        includeEmail: Boolean = true,
        attributeDataPresent: Boolean = true,
        gateDecision: GateDecision = GateDecision.Allow,
        unsubscribeUrl: String? = null,
        preferencesUrl: String? = null,
        tokenFails: Boolean = false,
        notificationTypeExists: Boolean = false,
        registryFails: Boolean = false,
        recipientCount: Int = 1,
        locales: List<String> = emptyList(),
    ): Fixture {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { Json }
        val recipientIds = (1..recipientCount).map { UUID.random() }
        val recipientId = recipientIds.first()
        val emails = recipientIds.mapIndexed { index, id -> id to "ada+$index@example.com" }.toMap()
        val profileService = mockk<ProfileService>()
        val gate = mockk<NotificationPreferenceGate>()
        val tracking = mockk<DeliveryTrackingService>()
        val messageQueue = mockk<JobQueue>(relaxed = true)
        val renderer = mockk<BmlMessageTemplateRendererService>()
        val registry = mockk<BmlMessageRegistryService>()
        val preferences = mockk<NotificationPreferenceService>()
        val types = mockk<NotificationTypeService>()
        val mailer = RecordingMailer(
            failure = when {
                mailerCancellation -> kotlinx.coroutines.CancellationException("cancelled")
                mailerFails -> IllegalStateException("provider rejected message")
                else -> null
            },
        )
        val recorded = mutableListOf<DeliveryEvent>()
        val recordedTemplates = mutableListOf<BmlMessageTemplateRender>()
        val renders = mutableListOf<RenderCall>()
        if (queueFails) {
            coEvery { messageQueue.enqueue(any()) } throws IllegalStateException("queue unavailable")
        } else {
            coEvery { messageQueue.enqueue(any()) } returns UUID.random()
        }
        for (id in recipientIds) {
            coEvery { profileService.getById(id) } returns Profile(
                id = id,
                type = ProfileType.GENERIC,
                name = "Ada",
                visibility = ProfileVisibility.USER,
            )
        }
        coEvery { profileService.addAttributesToBatch(any()) } coAnswers {
            if (!attributeDataPresent) return@coAnswers Unit
            val batch = firstArg<Batch<UUID, List<ProfileAttribute>>>()
            for (id in recipientIds) {
                val profileAttributes = mutableListOf(
                    ProfileAttribute(
                        profile = id,
                        typeId = "bosca.profiles.name",
                        visibility = ProfileVisibility.USER,
                        confidence = 100,
                        priority = 0,
                        source = "test",
                        attributes = buildJsonObject { put("name", "Ada Lovelace") },
                    ),
                )
                if (includeEmail) {
                    profileAttributes += ProfileAttribute(
                        profile = id,
                        typeId = "bosca.profiles.email",
                        visibility = ProfileVisibility.USER,
                        confidence = 100,
                        priority = 0,
                        source = "test",
                        attributes = buildJsonObject { put("email", emails.getValue(id)) },
                    )
                }
                locales.getOrNull(recipientIds.indexOf(id))?.let { locale ->
                    profileAttributes += ProfileAttribute(
                        profile = id,
                        typeId = "bosca.profiles.locale",
                        visibility = ProfileVisibility.USER,
                        confidence = 100,
                        priority = 0,
                        source = "test",
                        attributes = buildJsonObject { put("locale", locale) },
                    )
                }
                batch.setData(id, profileAttributes)
            }
        }
        for (id in recipientIds) {
            coEvery {
                gate.evaluate(id, DeliveryChannel.EMAIL, null, emails.getValue(id), false)
            } returns gateDecision
            coEvery {
                gate.evaluate(id, DeliveryChannel.EMAIL, any(), emails.getValue(id), false)
            } returns gateDecision
        }
        coEvery { tracking.recordEvent(any()) } coAnswers {
            recorded += firstArg<DeliveryEvent>()
        }
        coEvery { tracking.recordEvent(any(), any()) } coAnswers {
            recorded += firstArg<DeliveryEvent>()
            recordedTemplates += secondArg<BmlMessageTemplateRender>()
        }
        if (registryFails) {
            coEvery { registry.resolve(any()) } throws IllegalStateException("template is not registered")
        } else {
            coEvery { registry.resolve(any()) } returns ResolvedMessageTemplate("project", "template")
        }
        coEvery {
            renderer.render(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            renders += RenderCall(
                unsubscribeUrl = arg(6),
                preferencesUrl = arg(7),
                locale = arg(8),
            )
            RenderedEmail("Rendered", "<p>Rendered</p>", "Rendered", version = "20260804-v1")
        }
        coEvery { types.get(any()) } returns if (notificationTypeExists) {
            NotificationType(key = "marketing", name = "Marketing")
        } else {
            null
        }
        if (tokenFails) {
            coEvery { preferences.generateUnsubscribeToken(any(), any()) } throws
                IllegalStateException("token store unavailable")
        } else {
            coEvery { preferences.generateUnsubscribeToken(any(), any()) } returns "token"
        }

        return Fixture(
            recipientId = recipientId,
            recipientIds = recipientIds,
            recorded = recorded,
            recordedTemplates = recordedTemplates,
            messageQueue = messageQueue,
            mailer = mailer,
            renders = renders,
            notificationPreferences = preferences,
            notificationTypes = types,
            service = MessageServiceImpl(
                profileService = profileService,
                deviceService = mockk<DeviceService>(),
                messageQueue = messageQueue,
                mailerConfiguration = MailerConfiguration(
                    type = MailerType.SENDGRID,
                    from = MailerEmail("Bosca", "noreply@example.com"),
                    unsubscribeUrl = unsubscribeUrl,
                    preferencesUrl = preferencesUrl,
                ),
                mailer = mailer,
                sender = mockk<PushSender>(),
                deliveryTracking = tracking,
                gate = gate,
                bmlMessageRenderer = renderer,
                bmlMessageRegistry = registry,
                notificationPreferences = preferences,
                notificationTypes = types,
            ),
        )
    }

    private data class Fixture(
        val recipientId: UUID,
        val recipientIds: List<UUID>,
        val recorded: MutableList<DeliveryEvent>,
        val recordedTemplates: MutableList<BmlMessageTemplateRender>,
        val messageQueue: JobQueue,
        val mailer: RecordingMailer,
        val renders: List<RenderCall>,
        val notificationPreferences: NotificationPreferenceService,
        val notificationTypes: NotificationTypeService,
        val service: MessageServiceImpl,
    )

    private data class RenderCall(
        val unsubscribeUrl: String?,
        val preferencesUrl: String?,
        val locale: Locale?,
    )

    private class RecordingMailer(
        private val failure: Throwable? = null,
    ) : Mailer {

        override val maxRecipientsPerMessage: Int = 1000

        data class Address(
            override val name: String,
            override val email: String,
            override val customArguments: Map<String, String> = emptyMap(),
        ) : Email

        data class Body(val type: ContentType, override val content: String) : Content

        data class Message(
            override val from: Email,
            override val to: List<Email>,
            override val subject: String,
            override val content: List<Content>,
        ) : EmailMessage

        var sendCount = 0

        override suspend fun newEmail(
            name: String,
            email: String,
            customArguments: Map<String, String>,
        ): Email = Address(name, email, customArguments)

        override suspend fun newContent(type: ContentType, content: String): Content = Body(type, content)

        override suspend fun newMessage(
            from: Email,
            to: List<Email>,
            subject: String,
            content: List<Content>,
            inlineImages: List<InlineImage>,
            customArguments: Map<String, String>,
        ): EmailMessage = Message(from, to, subject, content)

        override suspend fun send(message: EmailMessage) {
            sendCount++
            failure?.let { throw it }
        }
    }
}
