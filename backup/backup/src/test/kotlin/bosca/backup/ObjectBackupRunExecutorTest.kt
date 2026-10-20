package bosca.backup

import bosca.di.BackupProviderRegistrar
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.di.provides
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.DelayException
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.sharedqueue.jobs.JobExecutor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.storage.configuration.StorageConfiguration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Generated queue/executor wiring and the narrow translation of destination contention. */
@OptIn(InternalDI::class)
class ObjectBackupRunExecutorTest {
    private val key = "a".repeat(64)
    private lateinit var service: ObjectBackupRunService
    private lateinit var queue: JobQueue

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        BackupProviderRegistrar().register()
        provides<Json>(singleton = true) { Json }
        service = mockk()
        provides<ObjectBackupRunService>(singleton = true, overrideExisting = true) { service }
        queue = mockk(relaxed = true)
        val factory = mockk<JobQueueFactory>()
        every { factory.create(BackupJobQueueNames.backupQueue) } returns queue
        provides<JobQueueFactory>(singleton = true) { factory }
        coEvery { queue.enqueue(any()) } returns UUID.random()
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `payload contains only its persisted run key and version`() {
        val definition = ObjectBackupRunJob(key, 7)
        val value = Json.encodeToJsonElement(ObjectBackupRunJob.serializer(), definition)
        assertEquals(JsonObject(mapOf("key" to JsonPrimitive(key), "version" to JsonPrimitive(7))), value)
        assertEquals(definition, Json.decodeFromJsonElement(ObjectBackupRunJob.serializer(), value))
        assertFailsWith<IllegalArgumentException> { ObjectBackupRunJob(" ", 0) }
        assertFailsWith<IllegalArgumentException> { ObjectBackupRunJob(key, -1) }
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromJsonElement(
                ObjectBackupRunJob.serializer(),
                JsonObject(mapOf("key" to JsonPrimitive(""), "version" to JsonPrimitive(0))),
            )
        }
    }

    @Test
    fun `generated enqueue helper selects the existing backup provider`(): Unit = runBlocking {
        val enqueuer = provide<JobConfigurationEnqueuer>(name = "object-backup-run")
        assertEquals(BackupJobQueueNames.backupJobQueue, enqueuer.queueName)
        assertSame(queue, ObjectBackupRunJob(key, 7).jobQueue())
        val job = ObjectBackupRunJob(key, 7).enqueue()
        assertIs<ObjectBackupRunExecutor>(job.newExecutor())
        coVerify(exactly = 1) { queue.enqueue(job) }
    }

    @Test
    fun `per-run executor lock delays duplicates instead of skipping their work`(): Unit = runBlocking {
        withExecutor(key) {
            assertEquals("object-backup-run:" + key, it.getLockId())
            assertFalse(it.skipExecutionIfLocked)
        }
        withExecutor("another") { assertEquals("object-backup-run:another", it.getLockId()) }
    }

    @Test
    fun `executor delegates the persisted key and pinned version to configured execution`(): Unit = runBlocking {
        coEvery { service.executeConfigured(key, 7, any<String>()) } returns mockk()
        withExecutor(key) { it.execute() }
        coVerify(exactly = 1) { service.executeConfigured(key, 7, any<String>()) }
        coVerify(exactly = 0) { service.executeConfigured(any()) }
        coVerify(exactly = 0) { service.executeConfigured(any(), any<Map<String, StorageConfiguration>>()) }
    }

    @Test
    fun `redelivery uses the same job identity for its checkpoint and keeps distinct jobs separate`(): Unit = runBlocking {
        val first = ObjectBackupRunJob(key, 7).prepare().apply { setPersistentId(UUID.random()) }
        val other = ObjectBackupRunJob(key, 7).prepare().apply { setPersistentId(UUID.random()) }
        coEvery { service.executeConfigured(key, 7, any<String>()) } returns mockk()
        withContext(queue.asCoroutineContext(first)) { repeat(2) { first.newExecutor().execute() } }
        withContext(queue.asCoroutineContext(other)) { other.newExecutor().execute() }
        coVerify(exactly = 2) { service.executeConfigured(key, 7, first.getId().toString()) }
        coVerify(exactly = 1) { service.executeConfigured(key, 7, other.getId().toString()) }
    }

    @Test
    fun `destination contention becomes delayed redelivery with its original cause`(): Unit = runBlocking {
        val failure = ObjectBackupDestinationBusyException("retained")
        coEvery { service.executeConfigured(key, 7, any<String>()) } throws failure
        val delayed = assertFailsWith<DelayException> { withExecutor(key) { it.execute() } }
        assertEquals(5.seconds, delayed.time)
        assertSame(failure, delayed.cause)
    }

    @Test
    fun `ordinary failures and cancellation propagate unchanged`(): Unit = runBlocking {
        for (failure in listOf(IOException("storage failed"), CancellationException("worker stopped"))) {
            coEvery { service.executeConfigured(key, 7, any<String>()) } throws failure
            val thrown = assertFailsWith<Exception> { withExecutor(key) { it.execute() } }
            assertEquals(failure::class, thrown::class)
            assertTrue(generateSequence(thrown as Throwable) { it.cause }.any { it === failure })
        }
    }

    private suspend fun withExecutor(key: String, block: suspend (JobExecutor) -> Unit) {
        val job = ObjectBackupRunJob(key, 7).prepare()
        withContext(queue.asCoroutineContext(job)) { block(job.newExecutor()) }
    }
}
