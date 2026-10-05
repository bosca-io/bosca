@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.workops.service

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.pipelines.service.PipelineService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobQueue
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.notification.NotificationChannel
import bosca.workops.model.notification.NotificationOutboxEntry
import bosca.workops.model.notification.NotificationOutboxAction
import bosca.workops.model.notification.NotificationPreference
import bosca.workops.model.notification.NotificationRecipient
import bosca.workops.model.notification.NotificationScheme
import bosca.workops.model.notification.WorkOpsEmailRequested
import bosca.workops.model.notification.WorkOpsChannelRequested
import bosca.workops.model.notification.dispatch
import bosca.workops.model.automation.AutomationEmailRequested
import bosca.workops.model.automation.dispatch as dispatchAutomationEmail
import bosca.workops.repository.NotificationOutboxRepository
import bosca.workops.repository.NotificationPreferenceRepository
import bosca.workops.repository.NotificationRepository
import bosca.workops.repository.NotificationSchemeRepository
import bosca.workops.repository.NotificationSubscriptionRepository
import bosca.workops.repository.TaskWatcherRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationServiceTest {

    private fun outboxEntry(
        sourceId: UUID = UUID.random(),
        channel: NotificationChannel = NotificationChannel.EMAIL,
        target: String = "profile",
        availableAt: OffsetDateTime? = null,
        digest: Boolean = false,
    ) = NotificationOutboxEntry(
        id = UUID.random(),
        sourceId = sourceId,
        event = "TASK_UPDATED",
        channel = channel,
        target = target,
        payload = JsonObject(emptyMap()),
        availableAt = availableAt,
        digest = digest,
    )

    @Test
    fun `delivery service provider resolves without concrete channel adapter providers`() = runTest {
        ProviderRegistry.clear()
        provides<PipelineService> { mockk(relaxed = true) }
        try {
            assertTrue(NotificationChannelDeliveryServiceImplProvider().get() is NotificationChannelDeliveryServiceImpl)
        } finally {
            ProviderRegistry.clear()
        }
    }

    @Test
    fun `notification schemes handle missing non-object valid and corrupt recipient data`() = runTest {
        val repository = mockk<NotificationSchemeRepository>()
        val missingId = UUID.random()
        val nonObject = NotificationScheme(
            id = UUID.random(),
            name = "Non-object",
            eventRecipients = JsonPrimitive("invalid"),
        )
        val validRecipients = Json.encodeToJsonElement(
            ListSerializer(NotificationRecipient.serializer()),
            listOf(NotificationRecipient.Reporter),
        )
        val mixed = NotificationScheme(
            id = UUID.random(),
            name = "Mixed",
            eventRecipients = JsonObject(
                mapOf(
                    "TASK_CREATED" to validRecipients,
                    "TASK_UPDATED" to JsonPrimitive("invalid"),
                ),
            ),
        )
        coEvery { repository.listAll() } returns listOf(mixed)
        coEvery { repository.getById(missingId) } returns null
        coEvery { repository.getById(nonObject.id) } returns nonObject
        coEvery { repository.getById(mixed.id) } returns mixed
        val service = NotificationSchemeServiceImpl(repository, Json)

        assertEquals(listOf(mixed), service.list())
        assertEquals(mixed, service.getById(mixed.id))
        assertNull(service.typed(missingId))
        assertTrue(service.typed(nonObject.id)?.recipientsByEvent?.isEmpty() == true)
        val typed = service.typed(mixed.id) ?: error("missing typed notification scheme")
        assertEquals(listOf(NotificationRecipient.Reporter), typed.recipientsByEvent["TASK_CREATED"])
        assertTrue(typed.recipientsByEvent["TASK_UPDATED"]?.isEmpty() == true)
    }

    @Test
    fun `decoded channels default for invalid shapes and return valid configured channels`() = runTest {
        val repository = mockk<NotificationPreferenceRepository>()
        val profileId = UUID.random()
        val service = NotificationPreferenceServiceImpl(repository, Json)
        val defaultChannels = setOf(NotificationChannel.IN_APP, NotificationChannel.EMAIL)

        assertEquals(
            defaultChannels,
            service.decodedChannels(NotificationPreference(profileId, JsonPrimitive("invalid")), "TASK_UPDATED"),
        )
        assertEquals(
            defaultChannels,
            service.decodedChannels(NotificationPreference(profileId, JsonObject(emptyMap())), "TASK_UPDATED"),
        )
        assertEquals(
            defaultChannels,
            service.decodedChannels(
                NotificationPreference(
                    profileId,
                    JsonObject(mapOf("TASK_UPDATED" to JsonPrimitive("not-a-channel-set"))),
                ),
                "TASK_UPDATED",
            ),
        )
        assertEquals(
            setOf(NotificationChannel.EMAIL, NotificationChannel.SLACK),
            service.decodedChannels(
                NotificationPreference(
                    profileId,
                    JsonObject(
                        mapOf(
                            "TASK_UPDATED" to JsonArray(
                                listOf(JsonPrimitive("EMAIL"), JsonPrimitive("SLACK")),
                            ),
                        ),
                    ),
                ),
                "TASK_UPDATED",
            ),
        )
    }

    @Test
    fun `inbox unread operations delegate with bounded limits`() = runTest {
        val repository = mockk<NotificationRepository>()
        val profileId = UUID.random()
        val sourceId = UUID.random()
        coEvery { repository.listUnread(profileId, 5, 1) } returns emptyList()
        coEvery { repository.markAllRead(profileId) } just Runs
        coEvery { repository.addOnce(sourceId, profileId, "TASK_UPDATED", null, null, null, "body", null) } returns null
        coEvery { repository.getBySource(sourceId, "TASK_UPDATED", profileId) } returns null
        val service = NotificationInboxServiceImpl(repository)

        assertTrue(service.listUnread(profileId, 5, 0).isEmpty())
        service.markAllRead(profileId)
        assertFailsWith<IllegalArgumentException> {
            service.addOnce(sourceId, profileId, "TASK_UPDATED", null, null, null, "body", null)
        }

        coVerify(exactly = 1) { repository.markAllRead(profileId) }
    }

    @Test
    fun `watcher mutations reject missing tasks before changing the repository`() = runTest {
        val repository = mockk<TaskWatcherRepository>()
        val tasks = mockk<TaskService>()
        val service = TaskWatcherServiceImpl(repository, tasks)
        val taskId = UUID.random()
        val profileId = UUID.random()
        coEvery { tasks.getById(taskId) } returns null

        for (operation in listOf<suspend () -> Unit>(
            { service.add(taskId, profileId) },
            { service.remove(taskId, profileId) },
        )) {
            val failure = assertFailsWith<WorkOpsNotFoundException> { operation() }
            assertEquals("Task", failure.type)
            assertEquals(taskId.toString(), failure.handle)
        }

        coVerify(exactly = 0) { repository.add(any(), any()) }
        coVerify(exactly = 0) { repository.remove(any(), any()) }
    }

    @Test
    fun `subscription collection and mutations delegate their composite keys`() = runTest {
        val repository = mockk<NotificationSubscriptionRepository>()
        val savedFilterId = UUID.random()
        val profileId = UUID.random()
        coEvery { repository.listAll() } returns emptyList()
        coEvery { repository.delete(savedFilterId, profileId) } just Runs
        coEvery { repository.touchRunAt(savedFilterId, profileId) } just Runs
        val service = NotificationSubscriptionServiceImpl(repository)

        assertTrue(service.listAll().isEmpty())
        service.delete(savedFilterId, profileId)
        service.touchRunAt(savedFilterId, profileId)

        coVerify(exactly = 1) { repository.delete(savedFilterId, profileId) }
        coVerify(exactly = 1) { repository.touchRunAt(savedFilterId, profileId) }
    }

    @Test
    fun `durable webhook and slack entries dispatch typed channel events`() = runTest {
        val requests = mutableListOf<WorkOpsChannelRequested>()
        val pipelineService = mockk<PipelineService>()
        coEvery { pipelineService.triggeredEventTypes() } returns setOf(
            NotificationChannelDeliveryServiceImpl.WORKOPS_CHANNEL_EVENT_TYPE,
        )
        val service = NotificationChannelDeliveryServiceImpl(pipelineService)
        val target = UUID.random().toString()
        val payload = buildJsonObject {
            put("event", JsonPrimitive("TASK_UPDATED"))
            put("body", JsonPrimitive("current"))
        }
        val entries = listOf(NotificationChannel.WEBHOOK, NotificationChannel.SLACK).map { channel ->
            NotificationOutboxEntry(
                id = UUID.random(),
                sourceId = UUID.random(),
                event = "TASK_UPDATED",
                channel = channel,
                target = target,
                payload = payload,
            )
        }
        mockkStatic("bosca.workops.model.notification.WorkOpsChannelRequestedExtKt")
        try {
            coEvery { any<WorkOpsChannelRequested>().dispatch() } coAnswers {
                requests += firstArg<WorkOpsChannelRequested>()
            }
            entries.forEach { service.deliver(it) }

            assertEquals(listOf(NotificationChannel.WEBHOOK, NotificationChannel.SLACK), requests.map { it.channel })
            assertEquals(entries.map { it.id }, requests.map { it.deliveryId })
            assertTrue(requests.all { it.target == target && it.payload == payload })
        } finally {
            unmockkStatic("bosca.workops.model.notification.WorkOpsChannelRequestedExtKt")
        }
    }

    @Test
    fun `durable channel handoff requires a triggered pipeline before dispatch`() = runTest {
        val pipelineService = mockk<PipelineService>()
        val service = NotificationChannelDeliveryServiceImpl(pipelineService)
        val profileId = UUID.random()
        val entityId = UUID.random()
        val entry = NotificationOutboxEntry(
            id = UUID.random(),
            sourceId = UUID.random(),
            event = "TASK_UPDATED",
            channel = NotificationChannel.EMAIL,
            target = profileId.toString(),
            payload = buildJsonObject {
                put("event", JsonPrimitive("TASK_UPDATED"))
                put("projectName", JsonPrimitive("Website"))
                put("entityType", JsonPrimitive("TASK"))
                put("entityId", JsonPrimitive(entityId.toString()))
                put("entityKey", JsonPrimitive("WEB-12"))
                put("title", JsonPrimitive("Update navigation"))
                put("linkPath", JsonPrimitive("/workops/tasks/$entityId"))
                put("body", JsonPrimitive("updated"))
            },
        )
        coEvery { pipelineService.triggeredEventTypes() } returns emptySet()

        val requests = mutableListOf<WorkOpsEmailRequested>()
        mockkStatic("bosca.workops.model.notification.WorkOpsEmailRequestedExtKt")
        try {
            coEvery { any<WorkOpsEmailRequested>().dispatch() } coAnswers {
                requests += firstArg<WorkOpsEmailRequested>()
            }
            assertFailsWith<IllegalStateException> { service.deliver(entry) }
            assertTrue(requests.isEmpty())

            coEvery { pipelineService.triggeredEventTypes() } returns setOf(
                NotificationChannelDeliveryServiceImpl.WORKOPS_EMAIL_EVENT_TYPE,
            )
            service.deliver(entry)

            assertEquals(entry.id, requests.single().deliveryId)
        } finally {
            unmockkStatic("bosca.workops.model.notification.WorkOpsEmailRequestedExtKt")
        }
    }

    @Test
    fun `durable automation email retry reuses the outbox id`() = runTest {
        val pipelineService = mockk<PipelineService>()
        coEvery { pipelineService.triggeredEventTypes() } returns setOf(
            NotificationChannelDeliveryServiceImpl.AUTOMATION_EMAIL_EVENT_TYPE,
        )
        val service = NotificationChannelDeliveryServiceImpl(pipelineService)
        val profileId = UUID.random()
        val requests = mutableListOf<AutomationEmailRequested>()
        mockkStatic("bosca.workops.model.automation.AutomationEmailRequestedExtKt")
        try {
            coEvery { any<AutomationEmailRequested>().dispatchAutomationEmail() } coAnswers {
                requests += firstArg<AutomationEmailRequested>()
            }
            val outboxId = UUID.random()
            val entry = NotificationOutboxEntry(
                id = outboxId,
                sourceId = UUID.random(),
                event = NotificationChannelDeliveryServiceImpl.AUTOMATION_EMAIL_EVENT,
                channel = NotificationChannel.EMAIL,
                target = profileId.toString(),
                payload = buildJsonObject {
                    put("subject", JsonPrimitive("Subject"))
                    put("body", JsonPrimitive("Body"))
                },
            )
            service.deliver(entry)
            service.deliver(entry)

            assertEquals(
                listOf(
                    AutomationEmailRequested(setOf(profileId), "Subject", "Body", outboxId),
                    AutomationEmailRequested(setOf(profileId), "Subject", "Body", outboxId),
                ),
                requests,
            )
        } finally {
            unmockkStatic("bosca.workops.model.automation.AutomationEmailRequestedExtKt")
        }
    }

    @Test
    fun `notification outbox service owns repository writes`() = runTest {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { Json }
        val repository = mockk<NotificationOutboxRepository>()
        val queue = mockk<JobQueue>(relaxed = true)
        val deliveryService = mockk<NotificationChannelDeliveryService>()
        val entry = NotificationOutboxEntry(
            id = UUID.random(),
            sourceId = UUID.random(),
            event = "OUTBOX",
            channel = NotificationChannel.SLACK,
            target = "target",
            payload = JsonObject(emptyMap()),
        )
        coEvery {
            repository.addOnce(any(), "OUTBOX", NotificationChannel.SLACK, "target", any(), null, false)
        } returns entry
        coEvery {
            repository.addRaw(any(), "AUTOMATION_COMMENT", NotificationOutboxAction.AUTOMATION_COMMENT, "task", any())
        } returns 1
        val service = NotificationOutboxServiceImpl(repository, queue, deliveryService)

        try {
            service.enqueue(NotificationChannel.SLACK, "target", "{\"event\":\"TASK_UPDATED\"}")
            service.enqueue("AUTOMATION_COMMENT", "task", "{\"body\":\"Automated\"}")

            coVerify(exactly = 1) {
                repository.addOnce(
                    any(), "OUTBOX", NotificationChannel.SLACK, "target", "{\"event\":\"TASK_UPDATED\"}", null, false,
                )
            }
            coVerify(exactly = 1) {
                repository.addRaw(
                    any(),
                    "AUTOMATION_COMMENT",
                    NotificationOutboxAction.AUTOMATION_COMMENT,
                    "task",
                    "{\"body\":\"Automated\"}",
                )
            }
        } finally {
            ProviderRegistry.clear()
        }
    }

    @Test
    fun `notification outbox enforces channel scheduling idempotency and recovery`() = runTest {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { Json }
        try {
            val repository = mockk<NotificationOutboxRepository>()
            val queue = mockk<JobQueue>(relaxed = true)
            val delivery = mockk<NotificationChannelDeliveryService>()
            val service = NotificationOutboxServiceImpl(repository, queue, delivery)
            val sourceId = UUID.random()
            val immediate = outboxEntry(sourceId = sourceId, channel = NotificationChannel.EMAIL)
            val future = outboxEntry(
                sourceId = UUID.random(),
                channel = NotificationChannel.WEBHOOK,
                availableAt = OffsetDateTime.now().plusDays(1),
            )
            val digest = outboxEntry(sourceId = UUID.random(), channel = NotificationChannel.SLACK, digest = true)
            coEvery {
                repository.addOnce(sourceId, "TASK_UPDATED", NotificationChannel.EMAIL, "profile", "{}", null, false)
            } returns null
            coEvery {
                repository.getBySource(sourceId, "TASK_UPDATED", NotificationChannel.EMAIL, "profile")
            } returns immediate
            coEvery {
                repository.addOnce(future.sourceId, "TASK_UPDATED", NotificationChannel.WEBHOOK, "profile", "{}", any(), false)
            } returns future
            coEvery {
                repository.addOnce(digest.sourceId, "TASK_UPDATED", NotificationChannel.SLACK, "profile", "{}", null, true)
            } returns digest

            assertEquals(
                immediate,
                service.enqueueOnce(sourceId, "TASK_UPDATED", NotificationChannel.EMAIL, "profile", "{}"),
            )
            service.enqueueOnce(
                future.sourceId,
                "TASK_UPDATED",
                NotificationChannel.WEBHOOK,
                "profile",
                "{}",
                future.availableAt,
                false,
            )
            service.enqueueOnce(digest.sourceId, "TASK_UPDATED", NotificationChannel.SLACK, "profile", "{}", null, true)
            coVerify(exactly = 1) { queue.enqueueIfAbsent(any()) }

            assertFailsWith<IllegalArgumentException> {
                service.enqueueOnce(UUID.random(), "TASK_UPDATED", NotificationChannel.IN_APP, "profile", "{}")
            }

            val disappeared = UUID.random()
            coEvery {
                repository.addOnce(disappeared, "TASK_UPDATED", NotificationChannel.EMAIL, "profile", "{}", null, false)
            } returns null
            coEvery {
                repository.getBySource(disappeared, "TASK_UPDATED", NotificationChannel.EMAIL, "profile")
            } returns null
            assertFailsWith<IllegalArgumentException> {
                service.enqueueOnce(disappeared, "TASK_UPDATED", NotificationChannel.EMAIL, "profile", "{}")
            }

            coEvery { repository.pendingImmediate(1_000) } returns listOf(immediate, future)
            assertEquals(2, service.recoverPending(9_999))
            coVerify(exactly = 3) { queue.enqueueIfAbsent(any()) }

            coEvery { repository.getPending(immediate.id) } returns immediate
            coEvery { repository.markSent(immediate.id) } just Runs
            coEvery { repository.markFailure(immediate.id, "failed") } just Runs
            assertEquals(immediate, service.getPending(immediate.id))
            service.markSent(immediate.id)
            service.markFailure(immediate.id, "failed")
        } finally {
            ProviderRegistry.clear()
        }
    }

    @Test
    fun `notification outbox digests group successes isolate failures and preserve cancellation`() = runTest {
        val repository = mockk<NotificationOutboxRepository>()
        val queue = mockk<JobQueue>(relaxed = true)
        val delivery = mockk<NotificationChannelDeliveryService>()
        val service = NotificationOutboxServiceImpl(repository, queue, delivery)
        val email = outboxEntry(channel = NotificationChannel.EMAIL, target = "first", digest = true)
        val emailTwo = outboxEntry(channel = NotificationChannel.EMAIL, target = "first", digest = true)
        val webhook = outboxEntry(channel = NotificationChannel.WEBHOOK, target = "second", digest = true)
        coEvery { repository.dueDigests(2_000) } returns listOf(email, emailTwo, webhook)
        coEvery { delivery.deliverDigest(listOf(email, emailTwo)) } just Runs
        coEvery { repository.markSent(listOf(email.id, emailTwo.id)) } returns 2
        coEvery { delivery.deliverDigest(listOf(webhook)) } throws IllegalStateException("webhook failed")
        coEvery { repository.markFailure(webhook.id, "webhook failed") } just Runs

        assertEquals(2, service.deliverDueDigests(9_999))
        coVerify { repository.markFailure(webhook.id, "webhook failed") }

        coEvery { repository.dueDigests(1) } returns listOf(webhook)
        coEvery { delivery.deliverDigest(listOf(webhook)) } throws CancellationException("cancelled")
        assertFailsWith<CancellationException> { service.deliverDueDigests(0) }

        val deliveryFailure = IllegalStateException()
        val recordFailure = IllegalArgumentException("outbox unavailable")
        coEvery { delivery.deliverDigest(listOf(webhook)) } throws deliveryFailure
        coEvery { repository.markFailure(webhook.id, any()) } coAnswers { throw recordFailure }
        assertEquals(0, service.deliverDueDigests(1))
        assertEquals(listOf(recordFailure), deliveryFailure.suppressed.toList())
        coVerify { repository.markFailure(webhook.id, "IllegalStateException") }
    }

    @Test
    fun `notification outbox string channels accept typed names and reject unknown actions`() = runTest {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { Json }
        try {
            val repository = mockk<NotificationOutboxRepository>()
            val queue = mockk<JobQueue>(relaxed = true)
            val service = NotificationOutboxServiceImpl(repository, queue, mockk())
            val entry = outboxEntry(channel = NotificationChannel.EMAIL)
            coEvery {
                repository.addOnce(any(), "OUTBOX", NotificationChannel.EMAIL, "profile", "{}", null, false)
            } returns entry

            service.enqueue("email", "profile", "{}")
            assertFailsWith<IllegalStateException> { service.enqueue("unknown", "profile", "{}") }
        } finally {
            ProviderRegistry.clear()
        }
    }

    @Test
    fun `channel delivery rejects malformed outbox rows and incomplete email payloads`() = runTest {
        val pipelineService = mockk<PipelineService>()
        coEvery { pipelineService.triggeredEventTypes() } returns setOf(
            NotificationChannelDeliveryServiceImpl.AUTOMATION_EMAIL_EVENT_TYPE,
            NotificationChannelDeliveryServiceImpl.WORKOPS_EMAIL_EVENT_TYPE,
            NotificationChannelDeliveryServiceImpl.WORKOPS_CHANNEL_EVENT_TYPE,
        )
        val service = NotificationChannelDeliveryServiceImpl(pipelineService)
        fun entry(
            event: String = "TASK_UPDATED",
            channel: NotificationChannel? = NotificationChannel.EMAIL,
            payload: kotlinx.serialization.json.JsonElement = JsonObject(emptyMap()),
        ) = NotificationOutboxEntry(
            id = UUID.random(),
            sourceId = UUID.random(),
            event = event,
            channel = channel,
            target = UUID.random().toString(),
            payload = payload,
        )

        assertTrue(assertFailsWith<IllegalStateException> {
            service.deliver(entry(payload = JsonPrimitive("not-an-object")))
        }.message.orEmpty().contains("payload is not an object"))
        assertTrue(assertFailsWith<IllegalStateException> {
            service.deliver(entry(channel = null))
        }.message.orEmpty().contains("has no notification channel"))
        assertTrue(assertFailsWith<IllegalStateException> {
            service.deliver(entry(channel = NotificationChannel.IN_APP))
        }.message.orEmpty().contains("IN_APP"))
        assertTrue(assertFailsWith<IllegalStateException> {
            service.deliver(
                entry(
                    event = NotificationChannelDeliveryServiceImpl.AUTOMATION_EMAIL_EVENT,
                    payload = buildJsonObject { put("body", JsonPrimitive("Body")) },
                ),
            )
        }.message.orEmpty().contains("has no subject"))
        assertTrue(assertFailsWith<IllegalStateException> {
            service.deliver(
                entry(
                    event = NotificationChannelDeliveryServiceImpl.AUTOMATION_EMAIL_EVENT,
                    payload = buildJsonObject { put("subject", JsonPrimitive("Subject")) },
                ),
            )
        }.message.orEmpty().contains("has no body"))
        assertTrue(assertFailsWith<IllegalStateException> {
            service.deliver(entry(payload = buildJsonObject { put("event", "TASK_UPDATED") }))
        }.message.orEmpty().contains("has no body"))
    }

    @Test
    fun `workops email delivery validates every routing field and preserves an actor name`() = runTest {
        val pipelineService = mockk<PipelineService>()
        coEvery { pipelineService.triggeredEventTypes() } returns setOf(
            NotificationChannelDeliveryServiceImpl.WORKOPS_EMAIL_EVENT_TYPE,
        )
        val service = NotificationChannelDeliveryServiceImpl(pipelineService)
        val profileId = UUID.random()
        val entityId = UUID.random()
        val valid = buildJsonObject {
            put("event", "TASK_UPDATED")
            put("projectName", "Website")
            put("entityType", "TASK")
            put("entityId", entityId.toString())
            put("entityKey", "WEB-12")
            put("title", "Update navigation")
            put("body", "Changed")
            put("actorName", "Alex")
            put("linkPath", "/workops/tasks/$entityId")
        }
        fun entry(payload: JsonObject) = NotificationOutboxEntry(
            id = UUID.random(),
            sourceId = UUID.random(),
            event = "TASK_UPDATED",
            channel = NotificationChannel.EMAIL,
            target = profileId.toString(),
            payload = payload,
        )

        val requests = mutableListOf<WorkOpsEmailRequested>()
        mockkStatic("bosca.workops.model.notification.WorkOpsEmailRequestedExtKt")
        try {
            coEvery { any<WorkOpsEmailRequested>().dispatch() } coAnswers {
                requests += firstArg<WorkOpsEmailRequested>()
            }
            val delivered = entry(valid)
            service.deliver(delivered)
            assertEquals("Alex", requests.single().actorName)
            assertEquals(delivered.id, requests.single().deliveryId)

            listOf("event", "projectName", "entityType", "entityKey", "title", "linkPath").forEach { key ->
                val failure = assertFailsWith<IllegalStateException> {
                    service.deliver(entry(JsonObject(valid - key)))
                }
                assertTrue(failure.message.orEmpty().contains("'$key'"), key)
            }
            listOf(null, "not-a-uuid").forEach { invalidId ->
                val payload = JsonObject(
                    if (invalidId == null) valid - "entityId"
                    else valid + ("entityId" to JsonPrimitive(invalidId)),
                )
                assertTrue(assertFailsWith<IllegalStateException> {
                    service.deliver(entry(payload))
                }.message.orEmpty().contains("valid 'entityId'"))
            }
        } finally {
            unmockkStatic("bosca.workops.model.notification.WorkOpsEmailRequestedExtKt")
        }
    }

    @Test
    fun `digest delivery validates grouping and dispatches email and channel summaries`() = runTest {
        val pipelineService = mockk<PipelineService>()
        coEvery { pipelineService.triggeredEventTypes() } returns setOf(
            NotificationChannelDeliveryServiceImpl.WORKOPS_EMAIL_EVENT_TYPE,
            NotificationChannelDeliveryServiceImpl.WORKOPS_CHANNEL_EVENT_TYPE,
        )
        val service = NotificationChannelDeliveryServiceImpl(pipelineService)
        val profileId = UUID.random()
        fun entry(
            channel: NotificationChannel? = NotificationChannel.EMAIL,
            target: String = profileId.toString(),
            event: String = "TASK_UPDATED",
            payload: kotlinx.serialization.json.JsonElement = JsonObject(emptyMap()),
        ) = NotificationOutboxEntry(
            id = UUID.random(),
            sourceId = UUID.random(),
            event = event,
            channel = channel,
            target = target,
            payload = payload,
            digest = true,
        )

        assertFailsWith<IllegalArgumentException> { service.deliverDigest(emptyList()) }
        assertTrue(assertFailsWith<IllegalStateException> {
            service.deliverDigest(listOf(entry(channel = null)))
        }.message.orEmpty().contains("has no notification channel"))
        assertFailsWith<IllegalArgumentException> {
            service.deliverDigest(listOf(entry(), entry(channel = NotificationChannel.SLACK)))
        }
        assertFailsWith<IllegalArgumentException> {
            service.deliverDigest(listOf(entry(), entry(target = "another-target")))
        }
        assertTrue(assertFailsWith<IllegalStateException> {
            service.deliverDigest(listOf(entry(payload = JsonPrimitive("bad"))))
        }.message.orEmpty().contains("payload is not an object"))
        assertFailsWith<IllegalArgumentException> {
            service.deliverDigest(
                listOf(entry(event = NotificationChannelDeliveryServiceImpl.AUTOMATION_EMAIL_EVENT)),
            )
        }
        assertTrue(assertFailsWith<IllegalStateException> {
            service.deliverDigest(listOf(entry(channel = NotificationChannel.IN_APP)))
        }.message.orEmpty().contains("IN_APP"))

        val emailRequests = mutableListOf<WorkOpsEmailRequested>()
        val channelRequests = mutableListOf<WorkOpsChannelRequested>()
        mockkStatic("bosca.workops.model.notification.WorkOpsEmailRequestedExtKt")
        mockkStatic("bosca.workops.model.notification.WorkOpsChannelRequestedExtKt")
        try {
            coEvery { any<WorkOpsEmailRequested>().dispatch() } coAnswers {
                emailRequests += firstArg<WorkOpsEmailRequested>()
            }
            coEvery { any<WorkOpsChannelRequested>().dispatch() } coAnswers {
                channelRequests += firstArg<WorkOpsChannelRequested>()
            }

            val emails = listOf(
                entry(payload = JsonObject(emptyMap())),
                entry(payload = buildJsonObject {
                    put("entityKey", "WEB-4")
                    put("title", "Published")
                    put("body", "Release is live")
                }),
            )
            service.deliverDigest(emails)
            assertEquals("WorkOps: Activity\n\nWEB-4: Published\nRelease is live", emailRequests.single().body)
            assertEquals("DAILY_DIGEST", emailRequests.single().event)
            assertEquals("2 updates", emailRequests.single().title)

            val payload = buildJsonObject { put("body", "Updated") }
            val webhook = listOf(entry(channel = NotificationChannel.WEBHOOK, target = "hook", payload = payload))
            val slack = listOf(entry(channel = NotificationChannel.SLACK, target = "channel", payload = payload))
            service.deliverDigest(webhook)
            service.deliverDigest(slack)
            assertEquals(listOf(NotificationChannel.WEBHOOK, NotificationChannel.SLACK), channelRequests.map { it.channel })
            assertTrue(channelRequests.all { it.payload.jsonObject["digest"] == JsonPrimitive(true) })
        } finally {
            unmockkStatic("bosca.workops.model.notification.WorkOpsEmailRequestedExtKt")
            unmockkStatic("bosca.workops.model.notification.WorkOpsChannelRequestedExtKt")
        }
    }
}
