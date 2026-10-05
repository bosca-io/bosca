package bosca.scheduler.jobs

import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.scheduler.service.SchedulerService
import bosca.serialization.OffsetDateTime
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(InternalDI::class)
class PurgeJobHistoryExecutorTest {
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setup() {
        provides<Json> { json }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @OptIn(Internal::class)
    @Test
    fun `execute purges completed history using configured retention`() = runBlocking {
        val schedulerService = mockk<SchedulerService>()
        val cutoff = slot<OffsetDateTime>()
        coEvery { schedulerService.purgeHistoryBefore(capture(cutoff)) } returns 7
        val before = OffsetDateTime.now().minusDays(45)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(PurgeJobHistoryJob(retentionDays = 45)),
            executor = PurgeJobHistoryExecutor::class,
        )

        withContext(mockk<JobQueue>().asCoroutineContext(job)) {
            PurgeJobHistoryExecutor(schedulerService).execute()
        }

        val after = OffsetDateTime.now().minusDays(45)
        assertTrue(!cutoff.captured.isBefore(before))
        assertTrue(!cutoff.captured.isAfter(after))
        coVerify(exactly = 1) { schedulerService.purgeHistoryBefore(cutoff.captured) }
    }

    @Test
    fun `job payload defaults to 30 retention days`() {
        val encoded = Json.encodeToString(PurgeJobHistoryJob.serializer(), PurgeJobHistoryJob())

        assertEquals("{}", encoded)
        assertEquals(30, Json.decodeFromString(PurgeJobHistoryJob.serializer(), encoded).retentionDays)
    }

    @Test
    fun `job payload rejects non-positive retention`() {
        val error = assertFailsWith<IllegalArgumentException> { PurgeJobHistoryJob(retentionDays = 0) }

        assertEquals("retentionDays must be greater than zero", error.message)
    }
}
