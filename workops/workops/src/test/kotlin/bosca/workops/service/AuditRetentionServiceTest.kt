package bosca.workops.service

import bosca.workops.repository.AuditRetentionRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals

class AuditRetentionServiceTest {

    @Test
    fun `rotate detaches only valid partitions older than the retention cutoff`() = runTest {
        val repository = mockk<AuditRetentionRepository>()
        val cutoff = YearMonth.now().minusMonths(12)
        val oldPartition = cutoff.minusMonths(1).partitionName()
        val boundaryPartition = cutoff.partitionName()
        val recentPartition = cutoff.plusMonths(1).partitionName()
        coEvery { repository.listPartitions() } returns listOf(
            oldPartition,
            boundaryPartition,
            recentPartition,
            "202001",
            "task_history_20201",
            "task_history_abcd01",
            "task_history_2020xx",
            "task_history_202000",
            "task_history_202013",
        )
        coEvery { repository.detach(any()) } just Runs
        val service = AuditRetentionServiceImpl(repository)

        assertEquals(listOf(oldPartition), service.rotate(12))

        coVerify(exactly = 1) { repository.detach(oldPartition) }
        coVerify(exactly = 0) { repository.detach(not(oldPartition)) }
    }

    private fun YearMonth.partitionName() = "task_history_%04d%02d".format(year, monthValue)
}
