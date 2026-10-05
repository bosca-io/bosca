package bosca.workops.service

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.artifact.ApiSurfaceReport
import bosca.workops.model.artifact.BreakingChangeLevel
import bosca.workops.model.dependency.DependencyDeclaration
import bosca.workops.model.dependency.DependencyStatus
import bosca.workops.model.dependency.DependencyType
import bosca.workops.model.okr.Objective
import bosca.workops.repository.ApiSurfaceReportRepository
import bosca.workops.repository.CompatibilityTestResultRepository
import bosca.workops.repository.DependencyDeclarationRepository
import bosca.workops.repository.ObjectiveRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportingServiceTest {

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
    }

    @AfterTest
    fun teardown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
    }

    @Test
    fun `compatibility result lookups delegate their complete keys`() = runTest {
        val repository = mockk<CompatibilityTestResultRepository>()
        val id = UUID.random()
        val consumerProjectId = UUID.random()
        val consumerVersionId = UUID.random()
        coEvery { repository.getById(id) } returns null
        coEvery { repository.listByConsumerVersion(consumerProjectId, consumerVersionId) } returns emptyList()
        val service = CompatibilityTestResultServiceImpl(repository)

        assertNull(service.getById(id))
        assertTrue(service.listByConsumerVersion(consumerProjectId, consumerVersionId).isEmpty())
    }

    @Test
    fun `objective lookups delegate each supported scope`() = runTest {
        val repository = mockk<ObjectiveRepository>()
        val objective = sampleObjective()
        val programId = UUID.random()
        val projectId = UUID.random()
        val portfolioId = UUID.random()
        coEvery { repository.getById(objective.id) } returns objective
        coEvery { repository.listForProgram(programId) } returns listOf(objective)
        coEvery { repository.listForProject(projectId) } returns listOf(objective)
        coEvery { repository.listForPortfolio(portfolioId) } returns listOf(objective)
        val service = ObjectiveServiceImpl(repository)

        assertEquals(objective, service.getById(objective.id))
        assertEquals(listOf(objective), service.listForProgram(programId))
        assertEquals(listOf(objective), service.listForProject(projectId))
        assertEquals(listOf(objective), service.listForPortfolio(portfolioId))
    }

    @Test
    fun `api surface lookups delegate and breaking reports only update compatible consumers`() = runTest {
        val repository = mockk<ApiSurfaceReportRepository>()
        val dependencyRepository = mockk<DependencyDeclarationRepository>()
        val report = sampleApiSurfaceReport()
        val current = sampleDependency(report.projectId, DependencyStatus.CURRENT)
        val incompatible = sampleDependency(report.projectId, DependencyStatus.INCOMPATIBLE)
        coEvery { repository.getById(report.id) } returns report
        coEvery { repository.listByVersion(report.versionId) } returns listOf(report)
        coEvery {
            repository.add(
                report.projectId,
                report.versionId,
                report.previousVersionId,
                report.artifactPublicationId,
                report.breakingChangeLevel.name,
                "[]",
                report.analyzerTool,
                report.reportUrl,
            )
        } returns report
        coEvery { dependencyRepository.listByProvider(report.projectId) } returns listOf(current, incompatible)
        coEvery {
            dependencyRepository.updateStatus(current.id, DependencyStatus.INCOMPATIBLE.name, current.version)
        } returns current.copy(status = DependencyStatus.INCOMPATIBLE, version = current.version + 1)
        val service = ApiSurfaceReportServiceImpl(repository, dependencyRepository)

        assertEquals(report, service.getById(report.id))
        assertEquals(listOf(report), service.listByVersion(report.versionId))
        assertEquals(report, service.register(report, Json))

        coVerify(exactly = 1) {
            dependencyRepository.updateStatus(current.id, DependencyStatus.INCOMPATIBLE.name, current.version)
        }
        coVerify(exactly = 0) {
            dependencyRepository.updateStatus(incompatible.id, any(), any())
        }
    }

    private fun sampleObjective() = Objective(
        id = UUID.random(),
        title = "Increase delivery confidence",
        periodStart = OffsetDateTime.parse("2026-07-01T00:00:00Z"),
        periodEnd = OffsetDateTime.parse("2026-09-30T23:59:59Z"),
        periodName = "FY26 Q3",
        ownerProfileId = UUID.random(),
    )

    private fun sampleApiSurfaceReport() = ApiSurfaceReport(
        id = UUID.random(),
        projectId = UUID.random(),
        versionId = UUID.random(),
        previousVersionId = UUID.random(),
        breakingChangeLevel = BreakingChangeLevel.MINOR_BREAKING,
        analyzerTool = "graphql-inspector",
    )

    private fun sampleDependency(providerProjectId: UUID, status: DependencyStatus) = DependencyDeclaration(
        id = UUID.random(),
        consumerProjectId = UUID.random(),
        providerProjectId = providerProjectId,
        providerVersionConstraint = "^2.0",
        dependencyType = DependencyType.CONTRACT,
        status = status,
        version = 4,
    )
}
