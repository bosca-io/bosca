package bosca.communications.service

import bosca.bml.message.client.BmlMessageServerClient
import bosca.communications.configuration.CommunicationsMigration
import bosca.communications.jobs.RecordDeliveryEventsJobExecutor
import bosca.communications.mailers.Content
import bosca.communications.mailers.ContentType
import bosca.communications.mailers.Email
import bosca.communications.mailers.EmailMessage
import bosca.communications.mailers.Mailer
import bosca.communications.mailers.MailerConfiguration
import bosca.communications.mailers.MailerEmail
import bosca.communications.mailers.MailerType
import bosca.communications.model.DeliveryEvent
import bosca.communications.model.DeliveryStatusType
import bosca.communications.model.Message
import bosca.communications.model.MessageChannel
import bosca.communications.model.MessageBmlTemplate
import bosca.configuration.service.ConfigurationService
import bosca.communications.repository.BmlMessageProjectRepositoryImpl
import bosca.communications.repository.DeliveryEventRepositoryImpl
import bosca.communications.repository.DeliveryStatusRepositoryImpl
import bosca.communications.repository.NotificationPreferenceRepositoryImpl
import bosca.communications.repository.NotificationPreferenceMappingRepositoryImpl
import bosca.communications.repository.NotificationSettingsRepositoryImpl
import bosca.communications.repository.NotificationTypeRepositoryImpl
import bosca.communications.repository.SuppressionListRepositoryImpl
import bosca.communications.repository.UnsubscribeTokenRepositoryImpl
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.FlywayMigration
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.graphql.Batch
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.security.model.Principal
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import com.auth0.jwt.interfaces.DecodedJWT
import com.sun.net.httpserver.HttpServer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.AfterClass
import bosca.test.resources.SharedPostgreSQLContainer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The BML email send path end to end against REAL infrastructure (the comms
 * leg): a real PostgreSQL (Testcontainers) running the communications migrations backs the
 * registry, preference gate, unsubscribe tokens, and delivery tracking; a real HTTP email
 * server double answers the render contract; the mailer is captured. Only the cross-domain
 * profile lookups are mocked.
 *
 * (The publish → event → hot-load → render leg is covered in bml-message-server's own suites —
 * MessageServerRoutesTest with real compiled jars and MessageReloaderNatsTest with real NATS.)
 */
@OptIn(InternalDI::class)
class BmlMessageSendEndToEndTest {

    // ── real infrastructure ─────────────────────────────────────────────────

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_test")
            withReuse(true)
            start()
        }

        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                    maxConnections = 5,
                ),
                key = "test",
            ),
        )

        private var schemaInitialized = false

        /** Render-contract double: personalizes from the request and echoes the pin. */
        private val renderServer: HttpServer = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/") { exchange ->
                if (exchange.requestURI.path.startsWith("/assets/")) {
                    lastAssetPath.set(exchange.requestURI.path)
                    assetHits.incrementAndGet()
                    val bytes = "img".toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", "image/png")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                    return@createContext
                }
                lastRenderPath.set(exchange.requestURI.path)
                lastRenderAuthorization.set(exchange.requestHeaders.getFirst("Authorization"))
                val body = exchange.requestBody.readBytes().decodeToString()
                lastRenderRequest.set(body)
                renderCount.incrementAndGet()
                val status = renderStatus.get()
                val response = if (status != 200) {
                    """{"error":"render exploded"}"""
                } else {
                    val request = Json.parseToJsonElement(body).jsonObject
                    val name = request["recipientName"]?.jsonPrimitive?.contentOrNull ?: "friend"
                    val version = request["version"]?.jsonPrimitive?.contentOrNull ?: "20260716-active"
                    val unsubscribe = request["unsubscribeUrl"]?.jsonPrimitive?.contentOrNull
                    val course = request["payload"]?.jsonObject?.get("courseName")?.jsonPrimitive?.contentOrNull
                    val html = buildString {
                        append("<body><p>Welcome $name to ${course ?: "the course"}</p>")
                        if (unsubscribe != null) append("""<a href="$unsubscribe">Unsubscribe</a>""")
                        append("</body>")
                    }
                    Json.encodeToString(
                        kotlinx.serialization.json.JsonObject.serializer(),
                        buildJsonObject {
                            put("project", "acme")
                            put("templateKey", "course-welcome")
                            put("version", version)
                            put(
                                "email",
                                buildJsonObject {
                                    put("subject", "Welcome $name")
                                    put("html", html)
                                    put("text", "Welcome $name")
                                    put(
                                        "images",
                                        kotlinx.serialization.json.buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("cid", "logo-png")
                                                    put("source", "logo.png")
                                                    put("mediaType", "image/png")
                                                    put("filename", "logo.png")
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                }
                val bytes = response.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        private val lastRenderPath = AtomicReference<String>()
        private val lastAssetPath = AtomicReference<String>()
        private val assetHits = AtomicInteger(0)
        private val lastRenderRequest = AtomicReference<String>()
        private val lastRenderAuthorization = AtomicReference<String>()
        private val renderCount = AtomicInteger(0)
        private val renderStatus = AtomicReference(200)

        @JvmStatic
        @AfterClass
        fun shutdown() {
            renderServer.stop(0)
            runBlocking { pool.close() }
            postgres.stop()
        }
    }

    // ── the real service stack over that infrastructure ─────────────────────

    private val types = NotificationTypeServiceImpl(NotificationTypeRepositoryImpl())
    private val preferenceMappings = NotificationPreferenceMappingServiceImpl(
        types,
        NotificationPreferenceMappingRepositoryImpl(),
        NotificationPreferenceRepositoryImpl(),
    )
    private val tracking = DeliveryTrackingServiceImpl(
        DeliveryEventRepositoryImpl(),
        DeliveryStatusRepositoryImpl(),
        SuppressionListRepositoryImpl(),
    )
    private val preferences = NotificationPreferenceServiceImpl(
        types,
        preferenceMappings,
        NotificationPreferenceRepositoryImpl(),
        NotificationSettingsRepositoryImpl(),
        UnsubscribeTokenRepositoryImpl(),
    )
    private val registry = BmlMessageRegistryServiceImpl(BmlMessageProjectRepositoryImpl())
    private val securityService = mockk<SecurityService>()
    private val serviceAccount = Principal(verified = true, anonymous = false)
    private val renderer = BmlMessageTemplateRendererServiceImpl(
        BmlMessageServerClient("http://localhost:${renderServer.address.port}"),
        registry,
        BmlMessageServerTokenProvider(securityService),
        mockk<ConfigurationService>(),
        Json { ignoreUnknownKeys = true },
    )
    private val mailer = CapturingMailer()
    private val messageQueue = mockk<JobQueue>(relaxed = true)
    private val queuedDeliveryEvents = mutableListOf<Job>()

    private val recipientId = UUID.random()
    private val recipientEmail = "sarah@example.com"
    private val profileService = mockk<ProfileService>()

    private val service = MessageServiceImpl(
        profileService = profileService,
        deviceService = mockk(relaxed = true),
        messageQueue = messageQueue,
        mailerConfiguration = MailerConfiguration(
            type = MailerType.SENDGRID,
            from = MailerEmail("Passion", "noreply@example.com"),
            unsubscribeUrl = "https://app.example.com/unsubscribe",
            preferencesUrl = "https://app.example.com/preferences",
        ),
        mailer = mailer,
        sender = mockk(relaxed = true),
        deliveryTracking = tracking,
        gate = NotificationPreferenceGateImpl(preferences, tracking),
        bmlMessageRenderer = renderer,
        bmlMessageRegistry = registry,
        notificationPreferences = preferences,
        notificationTypes = types,
    )

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json { ignoreUnknownKeys = true } }

        if (!schemaInitialized) {
            runBlocking { FlywayMigration(pool).migrate(listOf(CommunicationsMigration())) }
            schemaInitialized = true
        }
        withDb {
            bosca.db.transaction {
                for (table in listOf(
                    "bml_message_projects", "delivery_events", "delivery_status",
                    "suppression_list", "unsubscribe_tokens", "notification_preferences",
                    "notification_preference_mappings",
                )) {
                    connection().useStatement("DELETE FROM communications.$table") { it.execute() }
                }
            }
        }
        mailer.sent.clear()
        queuedDeliveryEvents.clear()
        renderCount.set(0)
        renderStatus.set(200)
        assetHits.set(0)
        coEvery { messageQueue.enqueue(any()) } coAnswers {
            queuedDeliveryEvents += firstArg<Job>()
            UUID.random()
        }
        coEvery { securityService.getPrincipalByIdentifier("sa") } returns serviceAccount
        coEvery { securityService.getPrincipalGroups(serviceAccount.id) } returns emptyList()
        coEvery { securityService.createJwtToken(serviceAccount, emptyMap()) } returns mockk<DecodedJWT> {
            every { token } returns "service-jwt"
            every { expiresAtAsInstant } returns java.time.Instant.now().plusSeconds(3600)
        }

        val profile = Profile(id = recipientId, type = ProfileType.GENERIC, name = "Sarah", visibility = ProfileVisibility.USER)
        coEvery { profileService.getById(recipientId) } returns profile
        coEvery { profileService.addAttributesToBatch(any()) } coAnswers {
            firstArg<Batch<UUID, List<ProfileAttribute>>>().setData(
                recipientId,
                listOf(
                    ProfileAttribute(
                        profile = recipientId,
                        typeId = "bosca.profiles.email",
                        visibility = ProfileVisibility.USER,
                        confidence = 100,
                        priority = 0,
                        source = "test",
                        attributes = buildJsonObject { put("email", recipientEmail) },
                    ),
                    // The recipient's language setting — single-recipient sends localize with it.
                    ProfileAttribute(
                        profile = recipientId,
                        typeId = "bosca.profiles.locale",
                        visibility = ProfileVisibility.USER,
                        confidence = 100,
                        priority = 0,
                        source = "test",
                        attributes = buildJsonObject { put("locale", "es-419") },
                    ),
                ),
            )
        }
    }

    private fun message() = Message(
        channels = listOf(MessageChannel.EMAIL),
        recipients = listOf(recipientId),
        bmlTemplate = MessageBmlTemplate(
            project = "acme",
            templateKey = "course-welcome",
            payload = Json.parseToJsonElement("""{"courseName":"Exploring Truth"}"""),
        ),
    )

    @Test
    fun `an event-key send resolves, renders with the pin and a real unsubscribe token, mails, and records SENT`() {
        withDb {
            registry.registerProject("acme", description = "Acme emails")
            registry.pinProjectVersion("acme", "20260701-abcd1234")

            service.sendNow(message())
        }

        // The render request carried the resolution outcome: mapped template, pin, payload, ids.
        assertEquals("/render/acme/course-welcome", lastRenderPath.get())
        assertEquals("Bearer service-jwt", lastRenderAuthorization.get())
        val request = Json.parseToJsonElement(lastRenderRequest.get()).jsonObject
        assertEquals("20260701-abcd1234", request["version"]?.jsonPrimitive?.content)
        assertEquals("Exploring Truth", request["payload"]?.jsonObject?.get("courseName")?.jsonPrimitive?.content)
        assertEquals(recipientId.toString(), request["recipientId"]?.jsonPrimitive?.content)
        assertEquals("Sarah", request["recipientName"]?.jsonPrimitive?.content)
        // The recipient's bosca.profiles.locale setting rides the wire.
        assertEquals("es-419", request["locale"]?.jsonPrimitive?.content)

        // The unsubscribe URL wraps a REAL stored token: using it opts the profile out.
        val unsubscribeUrl = request["unsubscribeUrl"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(unsubscribeUrl.startsWith("https://app.example.com/unsubscribe?token="), unsubscribeUrl)
        val token = unsubscribeUrl.substringAfter("token=")
        withDb {
            assertTrue(preferences.unsubscribeByToken(token), "the minted token must round-trip")
            assertEquals(recipientId, preferences.profileIdForToken(token))
        }

        // The mail that went out is the rendered one, TEXT then HTML.
        val sent = mailer.sent.single()
        assertEquals("Welcome Sarah", sent.subject)
        assertEquals(listOf(recipientEmail), sent.to.map { it.email })
        assertEquals(ContentType.TEXT, (sent.content[0] as CapturingMailer.C).type)
        assertEquals(ContentType.HTML, (sent.content[1] as CapturingMailer.C).type)
        val html = sent.content[1].content
        assertTrue("Exploring Truth" in html, html)
        assertTrue(unsubscribeUrl in html, html)

        // The render's bml-inline image rides the mail as an inline attachment, its bytes
        // fetched from the version-pinned asset route and cached per immutable version.
        val image = sent.inlineImages.single()
        assertEquals("logo-png", image.cid)
        assertEquals("image/png", image.mediaType)
        assertEquals("img", java.util.Base64.getDecoder().decode(image.contentBase64).decodeToString())
        assertEquals("/assets/acme/20260701-abcd1234/logo.png", lastAssetPath.get())
        assertEquals(1, assetHits.get())

        // A second send of the same message re-renders but does NOT refetch the image bytes —
        // and both sends share the SAME base64 instance (the cache holds the finished value;
        // per-send cost is a reference, not a copy).
        withDb { service.sendNow(message()) }
        assertEquals(2, mailer.sent.size)
        assertEquals("img", java.util.Base64.getDecoder().decode(mailer.sent.last().inlineImages.single().contentBase64).decodeToString())
        assertEquals(1, assetHits.get(), "immutable (project, version, source) bytes must fetch exactly once")
        assertTrue(
            mailer.sent[0].inlineImages.single().contentBase64 === mailer.sent[1].inlineImages.single().contentBase64,
            "renders must share the cached base64 instance, not copies",
        )
        drainDeliveryEvents()

        // The aggregate exists before provider handoff and is advanced to SENT afterward.
        withDb {
            val status = checkNotNull(tracking.getStatus(messageIdOf(), recipientId))
            assertEquals(DeliveryStatusType.SENT, status.status)
            assertEquals(1, status.attempts)
            assertTrue(status.lastAttemptAt != null)
            assertEquals("acme", status.bmlTemplate?.project)
            assertEquals("course-welcome", status.bmlTemplate?.templateKey)
            assertEquals("20260701-abcd1234", status.bmlTemplate?.version)
            assertEquals(
                "Exploring Truth",
                status.bmlTemplate?.parameters?.jsonObject?.get("courseName")?.jsonPrimitive?.content,
            )
            val events = tracking.getEventsForRecipient(recipientId)
            assertTrue(events.any { it.status == DeliveryStatusType.PENDING }, events.toString())
            assertTrue(events.any { it.status == DeliveryStatusType.SENT }, events.toString())
            assertEquals(2, tracking.countStatuses())
            assertEquals(2, tracking.getStatuses(offset = 0, limit = 25).size)
        }
    }

    @Test
    fun `a suppressed recipient is DROPPED before any render happens`() {
        withDb {
            tracking.suppress(recipientEmail, "hard bounce", "550")

            service.sendNow(message())
        }
        assertEquals(0, renderCount.get(), "a fully suppressed message must cost no render")
        assertTrue(mailer.sent.isEmpty())
        withDb {
            val events = tracking.getEventsForRecipient(recipientId)
            assertTrue(events.any { it.status == DeliveryStatusType.DROPPED }, events.toString())
        }
    }

    @Test
    fun `a render failure records FAILED, sends nothing, and rethrows for the job retry`() {
        renderStatus.set(500)
        val failedMessage = message()
        val failure = assertFailsWith<Exception> {
            withDb {
                service.sendNow(failedMessage)
            }
        }
        assertTrue("render exploded" in failure.message.orEmpty(), failure.message.orEmpty())
        assertTrue(mailer.sent.isEmpty())
        withDb {
            val events = tracking.getEventsForRecipient(recipientId)
            assertTrue(events.any { it.status == DeliveryStatusType.FAILED }, events.toString())
            val status = checkNotNull(tracking.getStatus(failedMessage.id, recipientId))
            assertEquals("acme", status.bmlTemplate?.project)
            assertEquals("course-welcome", status.bmlTemplate?.templateKey)
            assertEquals(
                "Exploring Truth",
                status.bmlTemplate?.parameters?.jsonObject?.get("courseName")?.jsonPrimitive?.content,
            )
        }
    }

    @Test
    fun `sendNow refuses an active transaction before provider handoff`() {
        val error = assertFailsWith<IllegalStateException> {
            withDb {
                bosca.db.transaction {
                    service.sendNow(message())
                }
            }
        }

        assertEquals(
            "MessageService.sendNow cannot run inside a database transaction; use send instead",
            error.message,
        )
        assertTrue(mailer.sent.isEmpty())
        assertEquals(0, renderCount.get())
        withDb {
            assertEquals(0, tracking.countStatuses())
            assertTrue(tracking.getEventsForRecipient(recipientId).isEmpty())
        }
    }

    @Test
    fun `a retried provider event is stored and applied exactly once`() {
        val messageId = UUID.random()
        val event = DeliveryEvent(
            providerEventId = "sendgrid-event-$messageId",
            messageId = messageId,
            recipientId = recipientId,
            status = DeliveryStatusType.DELIVERED,
            providerEvent = "delivered",
        )

        withDb {
            tracking.recordEvent(event)
            tracking.recordEvent(event)

            assertEquals(DeliveryStatusType.DELIVERED, tracking.getStatus(messageId, recipientId)?.status)
            assertEquals(1, tracking.getEventsForRecipient(recipientId).size)
            assertEquals(1, tracking.countStatuses())
        }
    }

    @Test
    fun `concurrent listeners store the same engagement event exactly once`() {
        val messageId = UUID.random()
        val event = DeliveryEvent(
            providerEventId = "bml-$messageId",
            messageId = messageId,
            recipientId = recipientId,
            status = DeliveryStatusType.OPENED,
            providerEvent = "open",
        )

        runBlocking {
            List(2) {
                async(Dispatchers.Default) {
                    withDbSuspending { tracking.recordEvent(event) }
                }
            }.awaitAll()
        }

        withDb {
            assertEquals(DeliveryStatusType.OPENED, tracking.getStatus(messageId, recipientId)?.status)
            assertEquals(1, tracking.getEventsForRecipient(recipientId).size)
            assertEquals(1, tracking.countStatuses())
        }
    }

    @Test
    fun `provider delivery in the same second advances the precise internal sent status`() {
        val messageId = UUID.random()
        val sentAt = bosca.serialization.OffsetDateTime.parse("2026-07-29T12:00:00.900Z")
        val deliveredAt = bosca.serialization.OffsetDateTime.parse("2026-07-29T12:00:00Z")

        withDb {
            tracking.recordEvent(DeliveryEvent(
                messageId = messageId,
                recipientId = recipientId,
                status = DeliveryStatusType.SENT,
                createdAt = sentAt,
            ))
            tracking.recordEvent(DeliveryEvent(
                providerEventId = "delivered-$messageId",
                messageId = messageId,
                recipientId = recipientId,
                status = DeliveryStatusType.DELIVERED,
                providerEvent = "delivered",
                createdAt = deliveredAt,
            ))
            tracking.recordEvent(DeliveryEvent(
                providerEventId = "processed-$messageId",
                messageId = messageId,
                recipientId = recipientId,
                status = DeliveryStatusType.SENT,
                providerEvent = "processed",
                createdAt = deliveredAt,
            ))

            val status = tracking.getStatus(messageId, recipientId)
            assertEquals(DeliveryStatusType.DELIVERED, status?.status)
            assertEquals(deliveredAt, status?.deliveredAt)
        }
    }

    @Test
    fun `local acceptance cannot regress a provider delivery from an earlier second`() {
        val messageId = UUID.random()
        val deliveredAt = bosca.serialization.OffsetDateTime.parse("2026-07-29T12:00:00Z")
        val acceptedAt = bosca.serialization.OffsetDateTime.parse("2026-07-29T12:00:02Z")

        withDb {
            tracking.recordEvent(DeliveryEvent(
                providerEventId = "delivered-$messageId",
                messageId = messageId,
                recipientId = recipientId,
                status = DeliveryStatusType.DELIVERED,
                providerEvent = "delivered",
                createdAt = deliveredAt,
            ))
            tracking.recordEvent(DeliveryEvent(
                providerEventId = "accepted-$messageId",
                messageId = messageId,
                recipientId = recipientId,
                status = DeliveryStatusType.SENT,
                createdAt = acceptedAt,
            ))

            val status = tracking.getStatus(messageId, recipientId)
            assertEquals(DeliveryStatusType.DELIVERED, status?.status)
            assertEquals(deliveredAt, status?.deliveredAt)
            assertEquals(deliveredAt, status?.updatedAt)
        }
    }

    @Test
    fun `an unregistered project still sends — registration only contributes the pin`() {
        withDb { service.sendNow(message()) }
        val request = Json.parseToJsonElement(lastRenderRequest.get()).jsonObject
        assertEquals("null", request["version"].toString())
        assertEquals(1, mailer.sent.size)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun messageIdOf(): UUID {
        val request = Json.parseToJsonElement(lastRenderRequest.get()).jsonObject
        return UUID.parse(request["messageId"]?.jsonPrimitive?.content.orEmpty())
    }

    private fun drainDeliveryEvents() {
        withDb {
            for (job in queuedDeliveryEvents) {
                withContext(messageQueue.asCoroutineContext(job)) {
                    RecordDeliveryEventsJobExecutor(tracking).execute()
                }
            }
        }
        queuedDeliveryEvents.clear()
    }

    private fun withDb(block: suspend () -> Unit) {
        runBlocking { withDbSuspending(block) }
    }

    private suspend fun withDbSuspending(block: suspend () -> Unit) {
        val manager = pool.connection()
        try {
            withContext(manager.asCoroutineContext()) { block() }
        } finally {
            withContext(NonCancellable) { manager.release() }
        }
    }

    private class CapturingMailer : Mailer {
        data class E(
            override val name: String,
            override val email: String,
            override val customArguments: Map<String, String>,
        ) : Email
        data class C(val type: ContentType, override val content: String) : Content
        data class M(
            override val from: Email,
            override val to: List<Email>,
            override val subject: String,
            override val content: List<Content>,
            val inlineImages: List<bosca.communications.mailers.InlineImage>,
        ) : EmailMessage

        val sent = mutableListOf<M>()

        override suspend fun newEmail(
            name: String,
            email: String,
            customArguments: Map<String, String>,
        ): Email = E(name, email, customArguments)
        override suspend fun newContent(type: ContentType, content: String): Content = C(type, content)

        override suspend fun newMessage(
            from: Email,
            to: List<Email>,
            subject: String,
            content: List<Content>,
            inlineImages: List<bosca.communications.mailers.InlineImage>,
            customArguments: Map<String, String>,
        ): EmailMessage = M(from, to, subject, content, inlineImages)

        override suspend fun send(message: EmailMessage) {
            sent.add(message as M)
        }
    }
}
