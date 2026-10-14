@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.analytics.jobs

import bosca.analytics.transform.fums.FumsReporter
import bosca.bible.providers.service.BibleProviderService
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.test.*

class FumsDeliveryExecutorTest {
    private val json = Json
    private val bibles = mockk<BibleProviderService>()
    private val reporter = mockk<FumsReporter>()
    private val queue = mockk<JobQueue>()
    private val usage = FumsDeliveryJob(UUID.random(), 2, "reader", "JHN.1", UUID.random(), "hashed-device", "session", "hashed-user")
    private lateinit var executor: FumsDeliveryExecutor

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json>(overrideExisting = true) { json }
        coEvery { bibles.getFumsToken(usage.bibleId, 2, "reader", "JHN.1", usage.audioId) } returns "private-token"
        coEvery { reporter.report(any(), any(), any(), any()) } just Runs
        executor = FumsDeliveryExecutor(bibles, reporter)
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    private suspend fun execute(payload: FumsDeliveryJob = usage) {
        val task = payload.prepare()
        withContext(queue.asCoroutineContext(task)) {
            executor.execute()
        }
    }

    @Test
    fun `resolves the private token and reports usage`() = runTest {
        execute()
        coVerify(exactly = 1) { bibles.getFumsToken(usage.bibleId, 2, "reader", "JHN.1", usage.audioId) }
        coVerify(exactly = 1) { reporter.report("private-token", "hashed-device", "session", "hashed-user") }
    }

    @Test
    fun `missing or expired tokens never fetch content or report usage`() = runTest {
        coEvery { bibles.getFumsToken(any(), any(), any(), any(), any()) } returns null
        execute()
        coVerify(exactly = 0) { reporter.report(any(), any(), any(), any()); bibles.getProvider(any()) }
    }

    @Test
    fun `failed reports propagate so the queue can retry`() = runTest {
        val error = IOException("FUMS unavailable")
        coEvery { reporter.report(any(), any(), any(), any()) } throws error
        assertEquals(error.message, assertFailsWith<IOException> { execute() }.message)
        coEvery { reporter.report(any(), any(), any(), any()) } just Runs
        execute()
        coVerify(exactly = 2) { reporter.report(any(), any(), any(), any()) }
    }

    @Test
    fun `token lookup failures and cancellation remain failures`() = runTest {
        coEvery { bibles.getFumsToken(any(), any(), any(), any(), any()) } throws IllegalStateException("lookup failed")
        assertFailsWith<IllegalStateException> { execute() }
        coEvery { bibles.getFumsToken(any(), any(), any(), any(), any()) } throws CancellationException("cancelled")
        assertFailsWith<CancellationException> { execute() }
        coVerify(exactly = 0) { reporter.report(any(), any(), any(), any()) }
    }

    @Test
    fun `transport cancellation propagates`() = runTest {
        coEvery { reporter.report(any(), any(), any(), any()) } throws CancellationException("cancelled")
        assertFailsWith<CancellationException> { execute() }
    }

    @Test
    fun `reference-only payload preserves the exact variant and recording`() {
        val encoded = json.encodeToString(FumsDeliveryJob.serializer(), usage)
        assertEquals(usage, json.decodeFromString(FumsDeliveryJob.serializer(), encoded))
        assertFalse(encoded.contains("token", ignoreCase = true))
    }

    @Test
    fun `delivery payload requires a complete Bible reference and usage context`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString(FumsDeliveryJob.serializer(), "{}")
        }
    }
}
