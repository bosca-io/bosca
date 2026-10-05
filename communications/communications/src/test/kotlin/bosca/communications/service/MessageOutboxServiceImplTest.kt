@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.communications.service

import bosca.communications.jobs.MessageOutboxDeliveryJob
import bosca.communications.jobs.MessageOutboxMaintenanceExecutor
import bosca.communications.model.Message
import bosca.communications.model.MessageBmlTemplate
import bosca.communications.model.MessageChannel
import bosca.communications.model.MessageOutboxEntry
import bosca.communications.repository.MessageOutboxRepository
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.FailException
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MessageOutboxServiceImplTest {

    private val json = Json

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `enqueue snapshots every delivery boundary before publishing stable jobs`() = runBlocking {
        val repository = mockk<MessageOutboxRepository>()
        val messages = mockk<MessageService>()
        val queue = mockk<JobQueue>()
        val sourceId = UUID.random()
        val recipientIds = List(3) { UUID.random() }
        val entries = mutableListOf<MessageOutboxEntry>()
        val jobs = mutableListOf<Job>()
        coEvery { repository.create(sourceId, any()) } coAnswers {
            json.decodeFromString(ListSerializer(Message.serializer()), secondArg<String>())
                .mapIndexed { position, message ->
                    MessageOutboxEntry(UUID.random(), sourceId, position, message)
                }
                .also(entries::addAll)
        }
        coEvery { queue.enqueueIfAbsent(capture(jobs)) } coAnswers { firstArg<Job>().getId() }
        val service = MessageOutboxServiceImpl(repository, messages, json, queue)

        service.enqueueOnce(
            sourceId,
            Message(
                channels = listOf(MessageChannel.EMAIL, MessageChannel.PUSH, MessageChannel.EMAIL),
                recipients = recipientIds,
                bmlTemplate = MessageBmlTemplate("bosca-messages", "chat-message"),
            ),
        )

        assertEquals(6, entries.size)
        assertEquals(
            listOf(
                listOf(MessageChannel.EMAIL),
                listOf(MessageChannel.EMAIL),
                listOf(MessageChannel.EMAIL),
                listOf(MessageChannel.PUSH),
                listOf(MessageChannel.PUSH),
                listOf(MessageChannel.PUSH),
            ),
            entries.map { it.message.channels },
        )
        assertEquals(List(6) { 1 }, entries.map { it.message.recipients.size })
        assertEquals(recipientIds + recipientIds, entries.map { it.message.recipients.single() })
        assertEquals(entries.map(MessageOutboxEntry::id), jobs.map(Job::getId))
        assertEquals(
            entries.map(MessageOutboxEntry::id),
            jobs.map {
                json.decodeFromJsonElement(MessageOutboxDeliveryJob.serializer(), it.getDefinition()).outboxId
            },
        )
    }

    @Test
    fun `repeated enqueue recovers persisted snapshot and republishes only pending rows`() = runBlocking {
        val repository = mockk<MessageOutboxRepository>()
        val queue = mockk<JobQueue>()
        val sourceId = UUID.random()
        val message = Message(channels = listOf(MessageChannel.PUSH), recipients = listOf(UUID.random()))
        val sent = MessageOutboxEntry(
            id = UUID.random(),
            sourceId = sourceId,
            position = 0,
            message = message,
            sentAt = OffsetDateTime.now(),
        )
        val pending = MessageOutboxEntry(UUID.random(), sourceId, 1, message)
        val jobs = mutableListOf<Job>()
        coEvery { repository.create(sourceId, any()) } returns emptyList()
        coEvery { repository.getBySource(sourceId) } returns listOf(sent, pending)
        coEvery { queue.enqueueIfAbsent(capture(jobs)) } coAnswers { firstArg<Job>().getId() }
        val service = MessageOutboxServiceImpl(repository, mockk(), json, queue)

        service.enqueueOnce(sourceId, message.copy(recipients = listOf(UUID.random(), UUID.random())))

        assertEquals(listOf(pending.id), jobs.map(Job::getId))
    }

    @Test
    fun `maintenance republishes bounded pending rows with their stable ids`() = runBlocking {
        val repository = mockk<MessageOutboxRepository>()
        val queue = mockk<JobQueue>()
        val pending = listOf(entry(), entry())
        val jobs = mutableListOf<Job>()
        coEvery { repository.getPending(1_000) } returns pending
        coEvery { queue.enqueueIfAbsent(capture(jobs)) } coAnswers { firstArg<Job>().getId() }
        val service = MessageOutboxServiceImpl(repository, mockk(), json, queue)

        assertEquals(2, service.recoverPending(5_000))

        assertEquals(pending.map(MessageOutboxEntry::id), jobs.map(Job::getId))
    }

    @Test
    fun `delivery marks success and completed rows are no-ops`() = runBlocking {
        val repository = mockk<MessageOutboxRepository>()
        val messages = mockk<MessageService>()
        val entry = entry()
        coEvery { repository.getPending(entry.id) } returns entry andThen null
        coEvery { messages.sendNow(entry.message) } returns Unit
        coEvery { repository.markSent(entry.id) } returns 1
        val service = service(repository, messages)

        service.deliver(entry.id)
        service.deliver(entry.id)

        coVerify(exactly = 1) { messages.sendNow(entry.message) }
        coVerify(exactly = 1) { repository.markSent(entry.id) }
        coVerify(exactly = 0) { repository.markFailure(any(), any()) }
    }

    @Test
    fun `delivery records retryable failures and preserves cancellation`() = runBlocking {
        val failedRepository = mockk<MessageOutboxRepository>()
        val failedMessages = mockk<MessageService>()
        val failedEntry = entry()
        coEvery { failedRepository.getPending(failedEntry.id) } returns failedEntry
        coEvery { failedMessages.sendNow(failedEntry.message) } throws IllegalStateException("provider unavailable")
        coEvery { failedRepository.markFailure(failedEntry.id, "provider unavailable") } returns 1

        assertFailsWith<IllegalStateException> {
            service(failedRepository, failedMessages).deliver(failedEntry.id)
        }
        coVerify(exactly = 1) { failedRepository.markFailure(failedEntry.id, "provider unavailable") }

        val cancelledRepository = mockk<MessageOutboxRepository>()
        val cancelledMessages = mockk<MessageService>()
        val cancelledEntry = entry()
        coEvery { cancelledRepository.getPending(cancelledEntry.id) } returns cancelledEntry
        coEvery { cancelledMessages.sendNow(cancelledEntry.message) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            service(cancelledRepository, cancelledMessages).deliver(cancelledEntry.id)
        }
        coVerify(exactly = 0) { cancelledRepository.markFailure(any(), any()) }
        coVerify(exactly = 0) { cancelledRepository.markSent(any()) }
    }

    @Test
    fun `terminal provider acceptance is marked sent without scheduling a duplicate retry`() = runBlocking {
        val repository = mockk<MessageOutboxRepository>()
        val messages = mockk<MessageService>()
        val entry = entry()
        coEvery { repository.getPending(entry.id) } returns entry
        coEvery { messages.sendNow(entry.message) } throws FailException("provider accepted")
        coEvery { repository.markSent(entry.id) } returns 1

        assertFailsWith<FailException> {
            service(repository, messages).deliver(entry.id)
        }

        coVerify(exactly = 1) { repository.markSent(entry.id) }
        coVerify(exactly = 0) { repository.markFailure(any(), any()) }
    }

    @Test
    fun `outbox state persistence never swallows cancellation`() = runBlocking {
        val terminalRepository = mockk<MessageOutboxRepository>()
        val terminalMessages = mockk<MessageService>()
        val terminalEntry = entry()
        coEvery { terminalRepository.getPending(terminalEntry.id) } returns terminalEntry
        coEvery { terminalMessages.sendNow(terminalEntry.message) } throws FailException("provider accepted")
        coEvery { terminalRepository.markSent(terminalEntry.id) } throws CancellationException("cancelled")

        val terminalCancellation = assertFailsWith<CancellationException> {
            service(terminalRepository, terminalMessages).deliver(terminalEntry.id)
        }
        assertEquals("provider accepted", terminalCancellation.suppressed.single().message)

        val retryRepository = mockk<MessageOutboxRepository>()
        val retryMessages = mockk<MessageService>()
        val retryEntry = entry()
        coEvery { retryRepository.getPending(retryEntry.id) } returns retryEntry
        coEvery { retryMessages.sendNow(retryEntry.message) } throws IllegalStateException("provider unavailable")
        coEvery {
            retryRepository.markFailure(retryEntry.id, "provider unavailable")
        } throws CancellationException("cancelled")

        val retryCancellation = assertFailsWith<CancellationException> {
            service(retryRepository, retryMessages).deliver(retryEntry.id)
        }
        assertEquals("provider unavailable", retryCancellation.suppressed.single().message)
    }

    @Test
    fun `maintenance propagates ordinary failures for scheduler history`() = runBlocking {
        val outbox = mockk<MessageOutboxService>()
        coEvery { outbox.recoverPending() } throws IllegalStateException("database unavailable")

        val failure = assertFailsWith<IllegalStateException> {
            MessageOutboxMaintenanceExecutor(outbox).execute()
        }

        assertEquals("database unavailable", failure.message)
        coVerify(exactly = 1) { outbox.recoverPending() }
    }

    @Test
    fun `maintenance completes after recovering pending rows`() = runBlocking {
        val outbox = mockk<MessageOutboxService>()
        coEvery { outbox.recoverPending() } returns 0

        MessageOutboxMaintenanceExecutor(outbox).execute()

        coVerify(exactly = 1) { outbox.recoverPending() }
    }

    @Test
    fun `maintenance preserves cancellation`() = runBlocking {
        val outbox = mockk<MessageOutboxService>()
        coEvery { outbox.recoverPending() } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            MessageOutboxMaintenanceExecutor(outbox).execute()
        }
        Unit
    }

    private fun service(
        repository: MessageOutboxRepository,
        messages: MessageService,
    ): MessageOutboxServiceImpl = MessageOutboxServiceImpl(repository, messages, json, mockk())

    private fun entry() = MessageOutboxEntry(
        id = UUID.random(),
        sourceId = UUID.random(),
        position = 0,
        message = Message(
            channels = listOf(MessageChannel.PUSH),
            recipients = listOf(UUID.random()),
        ),
    )
}
