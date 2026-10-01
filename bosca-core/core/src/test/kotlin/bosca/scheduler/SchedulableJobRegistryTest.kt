package bosca.scheduler

import bosca.queue.annotations.IJobDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SchedulableJobRegistryTest {

    private class TestJob : IJobDefinition

    @Test
    fun `registrars append stable job descriptions`() {
        val before = SchedulableJobRegistry.getSchedulableJobs().size
        val job = SchedulableJob(TestJob::class, "test job", "coverage registry job")
        assertEquals(job, job)
        assertFalse(job.equals(Any()))

        SchedulableJobRegistry.register(
            object : SchedulableJobRegistrar {
                override fun getSchedulableJobs() = listOf(job)
            },
            object : SchedulableJobRegistrar {
                override fun getSchedulableJobs() = emptyList<SchedulableJob>()
            },
        )

        assertEquals(before + 1, SchedulableJobRegistry.getSchedulableJobs().size)
        assertEquals(job, SchedulableJobRegistry.getSchedulableJobs().last())
    }
}
