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
    fun `payload contains only its persisted run key`() {
        val definition = ObjectBackupRunJob(key)
        val value = Json.encodeToJsonElement(ObjectBackupRunJob.serializer(), definition)
        assertEquals(JsonObject(mapOf("key" to JsonPrimitive(key))), value)
        assertEquals(definition, Json.decodeFromJsonElement(ObjectBackupRunJob.serializer(), value))
        assertFailsWith<IllegalArgumentException> { ObjectBackupRunJob(" ") }
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromJsonElement(ObjectBackupRunJob.serializer(), JsonObject(mapOf("key" to JsonPrimitive(""))))
        }
    }

    @Test
    fun `generated enqueue helper selects the existing backup provider`(): Unit = runBlocking {
        val enqueuer = provide<JobConfigurationEnqueuer>(name = "object-backup-run")
        assertEquals(BackupJobQueueNames.backupJobQueue, enqueuer.queueName)
        assertSame(queue, ObjectBackupRunJob(key).jobQueue())
        val job = ObjectBackupRunJob(key).enqueue()
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
    fun `executor delegates only the persisted key to configured execution`(): Unit = runBlocking {
        coEvery { service.executeConfigured(key) } returns mockk()
        withExecutor(key) { it.execute() }
        coVerify(exactly = 1) { service.executeConfigured(key) }
        coVerify(exactly = 0) { service.executeConfigured(any(), any()) }
    }

    @Test
    fun `destination contention becomes delayed redelivery with its original cause`(): Unit = runBlocking {
        val failure = ObjectBackupDestinationBusyException("retained")
        coEvery { service.executeConfigured(key) } throws failure
        val delayed = assertFailsWith<DelayException> { withExecutor(key) { it.execute() } }
        assertEquals(5.seconds, delayed.time)
        assertSame(failure, delayed.cause)
    }

    @Test
    fun `ordinary failures and cancellation propagate unchanged`(): Unit = runBlocking {
        for (failure in listOf(IOException("storage failed"), CancellationException("worker stopped"))) {
            coEvery { service.executeConfigured(key) } throws failure
            val thrown = assertFailsWith<Exception> { withExecutor(key) { it.execute() } }
            assertEquals(failure::class, thrown::class)
            assertTrue(generateSequence(thrown as Throwable) { it.cause }.any { it === failure })
        }
    }

    private suspend fun withExecutor(key: String, block: suspend (JobExecutor) -> Unit) {
        val job = ObjectBackupRunJob(key).prepare()
        withContext(queue.asCoroutineContext(job)) { block(job.newExecutor()) }
    }
}
