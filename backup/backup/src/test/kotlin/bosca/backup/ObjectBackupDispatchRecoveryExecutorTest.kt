package bosca.backup

import bosca.di.BackupProviderRegistrar
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.di.provides
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.sharedqueue.jobs.configuration.JobQueueNames
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Generated scheduler/enqueuer wiring and traversal of durable publication requests. */
@OptIn(InternalDI::class)
class ObjectBackupDispatchRecoveryExecutorTest {
    private lateinit var service: ObjectBackupDispatchService
    private lateinit var queue: JobQueue

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        BackupProviderRegistrar().register()
        provides<Json>(singleton = true) { Json }
        service = mockk()
        queue = mockk(relaxed = true)
        provides<ObjectBackupDispatchService>(singleton = true, overrideExisting = true) { service }
        provides<JobQueue>(name = JobQueueNames.commonJobQueue) { queue }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `payload has a default page size and rejects invalid sizes`() {
        val definition = ObjectBackupDispatchRecoveryJob()
        assertEquals(JsonObject(emptyMap()), Json.encodeToJsonElement(
            ObjectBackupDispatchRecoveryJob.serializer(), definition,
        ))
        assertEquals(definition, Json.decodeFromJsonElement(
            ObjectBackupDispatchRecoveryJob.serializer(), JsonObject(emptyMap()),
        ))
        assertFailsWith<IllegalArgumentException> { ObjectBackupDispatchRecoveryJob(0) }
        assertFailsWith<IllegalArgumentException> { ObjectBackupDispatchRecoveryJob(-1) }
    }

    @Test
    fun `generated scheduler enqueuer uses the common queue`(): Unit = runBlocking {
        val enqueuer = provide<JobConfigurationEnqueuer>(name = ObjectBackupDispatchRecoveryJob.NAME)
        assertEquals(JobQueueNames.commonJobQueue, enqueuer.queueName)
        assertSame(queue, enqueuer.queue())
        coEvery { service.recover(0, 100) } returns 0
        execute(ObjectBackupDispatchRecoveryJob())
        coVerify(exactly = 1) { service.recover(0, 100) }
    }

    @Test
    fun `recovery traverses every full page and stops at a partial page`(): Unit = runBlocking {
        coEvery { service.recover(0, 2) } returns 2
        coEvery { service.recover(2, 2) } returns 2
        coEvery { service.recover(4, 2) } returns 1
        execute(ObjectBackupDispatchRecoveryJob(2))
        coVerifyOrder {
            service.recover(0, 2)
            service.recover(2, 2)
            service.recover(4, 2)
        }
        coVerify(exactly = 3) { service.recover(any(), any()) }
    }

    @Test
    fun `exact full final page is followed by an empty read`(): Unit = runBlocking {
        coEvery { service.recover(0, 2) } returns 2
        coEvery { service.recover(2, 2) } returns 0
        execute(ObjectBackupDispatchRecoveryJob(2))
        coVerifyOrder {
            service.recover(0, 2)
            service.recover(2, 2)
        }
        coVerify(exactly = 2) { service.recover(any(), any()) }
    }

    @Test
    fun `empty work stops after the first page`(): Unit = runBlocking {
        coEvery { service.recover(0, 2) } returns 0
        execute(ObjectBackupDispatchRecoveryJob(2))
        coVerify(exactly = 1) { service.recover(any(), any()) }
    }

    @Test
    fun `publication failures and cancellation stop the sweep and propagate`(): Unit = runBlocking {
        for (failure in listOf(IOException("broker unavailable"), CancellationException("recovery stopped"))) {
            coEvery { service.recover(0, 2) } throws failure
            val thrown = assertFailsWith<Exception> { execute(ObjectBackupDispatchRecoveryJob(2)) }
            assertEquals(failure::class, thrown::class)
            assertTrue(generateSequence(thrown as Throwable) { it.cause }.any { it === failure })
        }
        coVerify(exactly = 0) { service.recover(2, 2) }
    }

    private suspend fun execute(definition: ObjectBackupDispatchRecoveryJob) {
        val enqueuer = provide<JobConfigurationEnqueuer>(name = ObjectBackupDispatchRecoveryJob.NAME)
        val value = Json.encodeToJsonElement(ObjectBackupDispatchRecoveryJob.serializer(), definition)
        val job = enqueuer.prepare(value)
        withContext(queue.asCoroutineContext(job)) { job.newExecutor().execute() }
    }
}
