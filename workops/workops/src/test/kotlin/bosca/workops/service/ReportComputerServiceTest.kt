package bosca.workops.service

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.PendingPhaseImplementationException
import bosca.workops.model.report.ReportInput
import bosca.workops.model.report.ReportKind
import bosca.workops.model.report.ReportPoint
import bosca.workops.repository.CategoryDistribution
import bosca.workops.repository.CreatedVsResolvedDay
import bosca.workops.repository.ReportComputerRepository
import bosca.workops.repository.SlaComplianceCounts
import bosca.workops.repository.SprintVelocityCounts
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReportComputerServiceTest {
    private val repository = mockk<ReportComputerRepository>()
    private val service = ReportComputerServiceImpl(repository)

    @Test
    fun `distribution reports preserve labels counts and scoped versus global queries`() = runTest {
        val projectId = UUID.random()
        coEvery { repository.countByTaskType(projectId) } returns listOf(
            CategoryDistribution("Bug", 3),
            CategoryDistribution("Task", 5),
        )
        coEvery { repository.countByTaskType(null) } returns listOf(CategoryDistribution("Task", 8))
        coEvery { repository.countByPriority(projectId) } returns listOf(CategoryDistribution("High", 2))
        coEvery { repository.countByPriority(null) } returns listOf(CategoryDistribution("Medium", 6))

        val scopedTypes = service.compute(ReportKind.ISSUE_TYPE_DISTRIBUTION, ReportInput.ScopeOnly(projectId))
        val globalTypes = service.compute(ReportKind.ISSUE_TYPE_DISTRIBUTION, ReportInput.Program(UUID.random()))
        val scopedPriorities = service.compute(ReportKind.PRIORITY_DISTRIBUTION, ReportInput.ScopeOnly(projectId))
        val globalPriorities = service.compute(ReportKind.PRIORITY_DISTRIBUTION, ReportInput.Program(UUID.random()))

        assertEquals(listOf("Bug", "Task"), scopedTypes.labels)
        assertEquals(listOf(ReportPoint("Bug", 3.0), ReportPoint("Task", 5.0)), scopedTypes.series.getValue("count"))
        assertEquals(listOf("Task"), globalTypes.labels)
        assertEquals(listOf(ReportPoint("High", 2.0)), scopedPriorities.series.getValue("count"))
        assertEquals(listOf(ReportPoint("Medium", 6.0)), globalPriorities.series.getValue("count"))
    }

    @Test
    fun `created versus resolved requires a date range and projects both series`() = runTest {
        val projectId = UUID.random()
        val from = OffsetDateTime.parse("2026-08-01T00:00:00Z")
        val to = OffsetDateTime.parse("2026-08-02T00:00:00Z")
        coEvery { repository.createdVsResolvedByDay(from, to, projectId) } returns listOf(
            CreatedVsResolvedDay("2026-08-01", 4, 1),
            CreatedVsResolvedDay("2026-08-02", 2, 3),
        )

        val report = service.compute(
            ReportKind.CREATED_VS_RESOLVED,
            ReportInput.DateRange(from, to, projectId),
        )

        assertEquals(listOf("2026-08-01", "2026-08-02"), report.labels)
        assertEquals(listOf(ReportPoint("2026-08-01", 4.0), ReportPoint("2026-08-02", 2.0)), report.series.getValue("created"))
        assertEquals(listOf(ReportPoint("2026-08-01", 1.0), ReportPoint("2026-08-02", 3.0)), report.series.getValue("resolved"))
        assertTrue(
            "DateRange" in assertFailsWith<PendingPhaseImplementationException> {
                service.compute(ReportKind.CREATED_VS_RESOLVED, ReportInput.ScopeOnly(projectId))
            }.message.orEmpty(),
        )
    }

    @Test
    fun `sla compliance and velocity expose every repository count and reject wrong velocity input`() = runTest {
        val projectId = UUID.random()
        val sprintId = UUID.random()
        coEvery { repository.slaCompliance(projectId) } returns SlaComplianceCounts(7, 2, 1, 4)
        coEvery { repository.slaCompliance(null) } returns SlaComplianceCounts(8, 3, 2, 5)
        coEvery { repository.sprintVelocity(sprintId) } returns SprintVelocityCounts(3600, 2400)

        val scoped = service.compute(ReportKind.SLA_COMPLIANCE, ReportInput.ScopeOnly(projectId))
        val global = service.compute(ReportKind.SLA_COMPLIANCE, ReportInput.Program(UUID.random()))
        val velocity = service.compute(ReportKind.VELOCITY, ReportInput.Sprint(sprintId))

        assertEquals(listOf(ReportPoint("MET", 7.0)), scoped.series.getValue("MET"))
        assertEquals(listOf(ReportPoint("BREACHED", 3.0)), global.series.getValue("BREACHED"))
        assertEquals(listOf(ReportPoint("STOPPED_EARLY", 1.0)), scoped.series.getValue("STOPPED_EARLY"))
        assertEquals(listOf(ReportPoint("OPEN", 5.0)), global.series.getValue("OPEN"))
        assertEquals(listOf(ReportPoint("committed", 3600.0)), velocity.series.getValue("committed"))
        assertEquals(listOf(ReportPoint("completed", 2400.0)), velocity.series.getValue("completed"))
        assertTrue(
            "Sprint" in assertFailsWith<PendingPhaseImplementationException> {
                service.compute(ReportKind.VELOCITY, ReportInput.ScopeOnly(projectId))
            }.message.orEmpty(),
        )
    }

    @Test
    fun `unimplemented report kinds fail explicitly`() = runTest {
        val implemented = setOf(
            ReportKind.ISSUE_TYPE_DISTRIBUTION,
            ReportKind.PRIORITY_DISTRIBUTION,
            ReportKind.CREATED_VS_RESOLVED,
            ReportKind.SLA_COMPLIANCE,
            ReportKind.VELOCITY,
        )
        for (kind in ReportKind.entries - implemented) {
            val error = assertFailsWith<PendingPhaseImplementationException> {
                service.compute(kind, ReportInput.ScopeOnly())
            }
            assertTrue(kind.name in error.message.orEmpty())
        }
        coVerify(exactly = 0) { repository.countByTaskType(any()) }
    }
}
