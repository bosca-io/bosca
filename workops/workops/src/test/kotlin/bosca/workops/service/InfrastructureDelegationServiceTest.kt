package bosca.workops.service

import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.workops.model.federation.FederationProjectFieldMask
import bosca.workops.model.project.ProjectRepository
import bosca.workops.model.worklog.WorklogEstimateAdjustmentMode
import bosca.workops.repository.AutomationExecutionLogRepository
import bosca.workops.repository.FederationFieldMaskRepository
import bosca.workops.repository.FederationPrincipalMappingRepository
import bosca.workops.repository.ProjectRepositoryRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InfrastructureDelegationServiceTest {

    @Test
    fun `project repository service preserves project and repository keys`() = runTest {
        val repository = mockk<ProjectRepositoryRepository>()
        val projectId = UUID.random()
        val repositoryId = UUID.random()
        val link = ProjectRepository(UUID.random(), projectId, repositoryId)
        coEvery { repository.list(projectId) } returns listOf(link)
        coEvery { repository.listByRepository(repositoryId) } returns listOf(link)
        coEvery { repository.add(projectId, repositoryId) } returns link
        coEvery { repository.remove(projectId, link.id) } just Runs
        val service = ProjectRepositoryServiceImpl(repository)

        assertEquals(listOf(link), service.list(projectId))
        assertEquals(listOf(link), service.listByRepository(repositoryId))
        assertEquals(link, service.add(projectId, repositoryId))
        service.remove(projectId, link.id)

        coVerify(exactly = 1) { repository.remove(projectId, link.id) }
    }

    @Test
    fun `automation log service bounds requested page sizes`() = runTest {
        val repository = mockk<AutomationExecutionLogRepository>()
        val ruleId = UUID.random()
        coEvery { repository.listForRule(ruleId, 5, 1) } returns emptyList()
        coEvery { repository.listForRule(ruleId, 5, 200) } returns emptyList()
        val service = AutomationExecutionLogServiceImpl(repository)

        assertTrue(service.listForRule(ruleId, 5, 0).isEmpty())
        assertTrue(service.listForRule(ruleId, 5, 500).isEmpty())
    }

    @Test
    fun `federation field masks and principal mappings preserve composite keys`() = runTest {
        val fieldMaskRepository = mockk<FederationFieldMaskRepository>()
        val mappingRepository = mockk<FederationPrincipalMappingRepository>()
        val projectId = UUID.random()
        val peerId = UUID.random()
        val profileId = UUID.random()
        val mask = FederationProjectFieldMask(projectId, peerId, 7)
        coEvery { fieldMaskRepository.get(projectId, peerId) } returns null
        coEvery { fieldMaskRepository.upsert(projectId, peerId, 7) } returns mask
        coEvery { mappingRepository.propose(peerId, "remote-42", profileId, "user@example.test") } just Runs
        coEvery { mappingRepository.accept(peerId, "remote-42", profileId) } just Runs
        coEvery { mappingRepository.resolve(peerId, "remote-42") } returns profileId
        val fieldMasks = FederationFieldMaskServiceImpl(fieldMaskRepository)
        val mappings = FederationPrincipalMappingServiceImpl(mappingRepository)

        assertNull(fieldMasks.get(projectId, peerId))
        assertEquals(mask, fieldMasks.upsert(projectId, peerId, 7))
        mappings.propose(peerId, "remote-42", profileId, "user@example.test")
        mappings.accept(peerId, "remote-42", profileId)
        assertEquals(profileId, mappings.resolve(peerId, "remote-42"))
    }

    @Test
    fun `worklog mode resolver retains the documented fallback`() = runTest {
        val repository = mockk<bosca.workops.repository.ProjectRepository>()

        assertEquals(
            WorklogEstimateAdjustmentMode.AUTO_REDUCE,
            WorklogModeResolverImpl(repository).modeFor(UUID.random()),
        )
    }

    @Test
    fun `environment and requirement permission evaluators expose their dependencies`() {
        val securityService = mockk<SecurityService>()
        val groupEvaluator = mockk<GroupEvaluator>()
        val environmentService = mockk<EnvironmentService>()
        val requirementService = mockk<RequirementService>()
        val environment = EnvironmentPermissionEvaluator(environmentService, securityService, groupEvaluator)
        val requirement = RequirementPermissionEvaluator(requirementService, securityService, groupEvaluator)

        assertSame(environmentService, environment.service)
        assertSame(requirementService, requirement.service)
    }
}
