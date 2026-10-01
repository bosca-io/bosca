package bosca.workops.service

import bosca.git.model.EnvironmentDefinition
import bosca.git.model.PipelineEnvironmentsSynced
import bosca.serialization.UUID
import bosca.workops.model.OptimisticLockFailedException
import bosca.workops.model.environment.CreateEnvironmentInput
import bosca.workops.model.environment.Environment
import bosca.workops.model.environment.EnvironmentType
import bosca.workops.model.environment.UpdateEnvironmentInput
import bosca.workops.model.project.Project
import bosca.workops.model.project.ProjectRepository as ProjectRepositoryLink
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PipelineEnvironmentSyncTest {

    private val environmentService = mockk<EnvironmentService>(relaxed = true)
    private val environmentTypeService = mockk<EnvironmentTypeService>(relaxed = true)
    private val projectRepositories = mockk<ProjectRepositoryService>()
    private val projectService = mockk<ProjectService>()

    private val sync = PipelineEnvironmentSync(
        environmentService, environmentTypeService, projectRepositories, projectService,
    )

    private val programId = UUID.random()
    private val productionType = EnvironmentType(id = UUID.random(), name = "production")
    private val stagingType = EnvironmentType(id = UUID.random(), name = "staging")

    private fun environment(key: String, name: String = key, approval: Boolean = false, version: Long = 3) =
        Environment(
            id = UUID.random(), programId = programId, key = key, name = name,
            requiresApproval = approval, typeId = stagingType.id, version = version,
        )

    @Test
    fun `creates missing environments with humanized names and matching types`() = runTest {
        coEvery { environmentService.listByProgram(programId) } returns emptyList()
        coEvery { environmentTypeService.list() } returns listOf(stagingType, productionType)
        val created = slot<CreateEnvironmentInput>()
        coEvery { environmentService.create(capture(created)) } answers {
            environment(key = created.captured.key, name = created.captured.name)
        }

        sync.apply(
            programId,
            mapOf("staging" to EnvironmentDefinition(deployOnRelease = true)),
        )

        assertEquals("staging", created.captured.key)
        assertEquals("Staging", created.captured.name)
        assertEquals(stagingType.id, created.captured.typeId)
        assertEquals(false, created.captured.requiresApproval)
    }

    @Test
    fun `a key with no matching type grows the type catalog`() = runTest {
        coEvery { environmentService.listByProgram(programId) } returns emptyList()
        coEvery { environmentTypeService.list() } returns listOf(stagingType, productionType)
        val qaType = EnvironmentType(id = UUID.random(), name = "qa-eu")
        coEvery { environmentTypeService.create("qa-eu", any(), any()) } returns qaType
        val created = slot<CreateEnvironmentInput>()
        coEvery { environmentService.create(capture(created)) } answers {
            environment(key = created.captured.key, name = created.captured.name)
        }

        sync.apply(programId, mapOf("qa-eu" to EnvironmentDefinition()))

        assertEquals("Qa Eu", created.captured.name)
        assertEquals(qaType.id, created.captured.typeId)
        coVerify { environmentTypeService.create("qa-eu", any(), any()) }
    }

    @Test
    fun `updates only the YAML-owned fields of an existing environment`() = runTest {
        val production = environment("production", name = "Production (EU)", approval = false)
        coEvery { environmentService.listByProgram(programId) } returns listOf(production)
        coEvery { environmentService.promotionSourceIds(production.id) } returns emptyList()
        val input = slot<UpdateEnvironmentInput>()
        coEvery { environmentService.update(production.id, capture(input), production.version) } answers {
            production.copy(requiresApproval = true, version = production.version + 1)
        }

        sync.apply(programId, mapOf("production" to EnvironmentDefinition(approval = true)))

        // requiresApproval flipped; the curated display name and type travelled through untouched.
        assertEquals(true, input.captured.requiresApproval)
        assertEquals("Production (EU)", input.captured.name)
        assertEquals(production.typeId, input.captured.typeId)
        coVerify(exactly = 0) { environmentService.create(any()) }
    }

    @Test
    fun `an unchanged environment is not rewritten`() = runTest {
        val production = environment("production", approval = true)
        coEvery { environmentService.listByProgram(programId) } returns listOf(production)
        coEvery { environmentService.promotionSourceIds(production.id) } returns emptyList()

        sync.apply(programId, mapOf("production" to EnvironmentDefinition(approval = true)))

        coVerify(exactly = 0) { environmentService.update(any(), any(), any()) }
    }

    @Test
    fun `promotion edges are replaced from promotes-from keys`() = runTest {
        val staging = environment("staging")
        val production = environment("production")
        coEvery { environmentService.listByProgram(programId) } returns listOf(staging, production)
        coEvery { environmentService.promotionSourceIds(staging.id) } returns emptyList()
        coEvery { environmentService.promotionSourceIds(production.id) } returns emptyList()
        val input = slot<UpdateEnvironmentInput>()
        coEvery { environmentService.update(production.id, capture(input), production.version) } answers {
            production.copy(version = production.version + 1)
        }

        sync.apply(
            programId,
            mapOf(
                "staging" to EnvironmentDefinition(deployOnRelease = true),
                "production" to EnvironmentDefinition(promotesFrom = "staging"),
            ),
        )

        assertEquals(listOf(staging.id), input.captured.promotionSourceIds)
        // staging has no promotes-from and no current sources: nothing to rewrite.
        coVerify(exactly = 0) { environmentService.update(staging.id, any(), any()) }
    }

    @Test
    fun `a promotes-from naming an undeclared key clears the edges and skips the add`() = runTest {
        val production = environment("production")
        coEvery { environmentService.listByProgram(programId) } returns listOf(production)
        coEvery { environmentService.promotionSourceIds(production.id) } returns listOf(UUID.random())
        val input = slot<UpdateEnvironmentInput>()
        coEvery { environmentService.update(production.id, capture(input), production.version) } answers {
            production.copy(version = production.version + 1)
        }

        sync.apply(programId, mapOf("production" to EnvironmentDefinition(promotesFrom = "ghost")))

        assertEquals(emptyList(), input.captured.promotionSourceIds)
    }

    @Test
    fun `concurrent environment edits defer both approval and topology updates`() = runTest {
        val approval = environment("approval", approval = false)
        val topology = environment("topology", approval = false)
        coEvery { environmentService.listByProgram(programId) } returns listOf(approval, topology)
        coEvery { environmentService.promotionSourceIds(approval.id) } returns emptyList()
        coEvery { environmentService.promotionSourceIds(topology.id) } returns listOf(UUID.random())
        coEvery { environmentService.update(approval.id, any(), approval.version) } throws
            OptimisticLockFailedException("Environment", approval.id)
        coEvery { environmentService.update(topology.id, any(), topology.version) } throws
            OptimisticLockFailedException("Environment", topology.id)

        sync.apply(
            programId,
            mapOf(
                "approval" to EnvironmentDefinition(approval = true),
                "topology" to EnvironmentDefinition(),
            ),
        )

        coVerify(exactly = 1) { environmentService.update(approval.id, any(), approval.version) }
        coVerify(exactly = 1) { environmentService.update(topology.id, any(), topology.version) }
    }

    @Test
    fun `an event fans out to every program linked to the repository and skips unlinked repositories`() = runTest {
        val repositoryId = UUID.random()
        val projectA = UUID.random()
        val missingProject = UUID.random()
        val otherProgramId = UUID.random()
        coEvery { projectRepositories.listByRepository(repositoryId) } returns listOf(
            ProjectRepositoryLink(id = UUID.random(), projectId = projectA, repositoryId = repositoryId),
            ProjectRepositoryLink(id = UUID.random(), projectId = missingProject, repositoryId = repositoryId),
        )
        coEvery { projectService.getById(projectA) } returns
            Project(id = projectA, programId = otherProgramId, key = "P", name = "P", ownerProfileId = UUID.random())
        coEvery { projectService.getById(missingProject) } returns null
        val production = environment("production").copy(programId = otherProgramId)
        coEvery { environmentService.listByProgram(otherProgramId) } returns listOf(production)
        coEvery { environmentService.promotionSourceIds(production.id) } returns emptyList()

        sync.apply(
            PipelineEnvironmentsSynced(
                repositoryId = repositoryId,
                pipelineId = UUID.random(),
                environments = mapOf("production" to EnvironmentDefinition()),
            ),
        )
        coVerify { environmentService.listByProgram(otherProgramId) }

        // Unlinked repository: nothing consulted, nothing written.
        val unlinked = UUID.random()
        coEvery { projectRepositories.listByRepository(unlinked) } returns emptyList()
        sync.apply(
            PipelineEnvironmentsSynced(
                repositoryId = unlinked,
                pipelineId = UUID.random(),
                environments = mapOf("production" to EnvironmentDefinition()),
            ),
        )
        coVerify(exactly = 1) { environmentService.listByProgram(any()) }
    }
}
