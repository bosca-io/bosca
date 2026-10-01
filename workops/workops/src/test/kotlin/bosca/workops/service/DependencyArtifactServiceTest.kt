package bosca.workops.service

import bosca.serialization.UUID
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.artifact.ArtifactPublication
import bosca.workops.model.artifact.ArtifactType
import bosca.workops.model.artifact.PublicationStatus
import bosca.workops.model.artifact.RegisterArtifactInput
import bosca.workops.model.dependency.CreateDependencyDeclarationInput
import bosca.workops.model.dependency.DependencyDeclaration
import bosca.workops.model.dependency.DependencyStatus
import bosca.workops.model.dependency.DependencyType
import bosca.workops.model.project.Program
import bosca.workops.model.project.Project
import bosca.workops.repository.ArtifactPublicationRepository
import bosca.workops.repository.DependencyDeclarationRepository
import bosca.workops.repository.ProgramRepository
import bosca.workops.repository.ProjectRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DependencyArtifactServiceTest {

    private val projectRepository = mockk<ProjectRepository>()
    private val programRepository = mockk<ProgramRepository>()
    private val dispatcher = mockk<AutomationDispatcher>()

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
    fun `dependency lookups declaration validation and removal delegate correctly`() = runTest {
        val repository = mockk<DependencyDeclarationRepository>()
        val service = DependencyDeclarationServiceImpl(repository, projectRepository, programRepository, dispatcher)
        val dependency = dependency()
        val input = CreateDependencyDeclarationInput(
            consumerProjectId = dependency.consumerProjectId,
            consumerVersionId = UUID.random(),
            providerProjectId = dependency.providerProjectId,
            providerVersionConstraint = "^6.0.0",
            resolvedProviderVersionId = UUID.random(),
            dependencyType = DependencyType.RUNTIME,
            artifactCoordinates = "io.bosca:core",
        )
        val consumer = project(dependency.consumerProjectId)
        val provider = project(dependency.providerProjectId)
        coEvery { repository.getById(dependency.id) } returns dependency
        coEvery { repository.listByConsumer(dependency.consumerProjectId) } returns listOf(dependency)
        coEvery { repository.listByProvider(dependency.providerProjectId) } returns listOf(dependency)
        coEvery { repository.delete(dependency.id) } just Runs
        coEvery { projectRepository.getById(input.consumerProjectId) } returns consumer
        coEvery { projectRepository.getById(input.providerProjectId) } returns provider
        coEvery { repository.add(any(), any(), any(), any(), any(), any(), any(), any()) } returns dependency

        assertEquals(dependency, service.getById(dependency.id))
        assertEquals(listOf(dependency), service.listByConsumer(dependency.consumerProjectId))
        assertEquals(listOf(dependency), service.listByProvider(dependency.providerProjectId))
        service.remove(dependency.id)
        assertEquals(dependency, service.declare(input))
        coVerify(exactly = 1) {
            repository.add(
                input.consumerProjectId,
                input.consumerVersionId,
                input.providerProjectId,
                "^6.0.0",
                input.resolvedProviderVersionId,
                "RUNTIME",
                "io.bosca:core",
                "CURRENT",
            )
        }

        assertFailsWith<WorkOpsValidationException> {
            service.declare(input.copy(providerProjectId = input.consumerProjectId))
        }
        coEvery { projectRepository.getById(input.consumerProjectId) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.declare(input) }
        coEvery { projectRepository.getById(input.consumerProjectId) } returns consumer
        coEvery { projectRepository.getById(input.providerProjectId) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.declare(input) }
    }

    @Test
    fun `dependency updates cover current outdated scope failures and cancellation`() = runTest {
        val repository = mockk<DependencyDeclarationRepository>()
        val service = DependencyDeclarationServiceImpl(repository, projectRepository, programRepository, dispatcher)
        val dependency = dependency()
        val resolvedVersionId = UUID.random()
        coEvery { repository.updateStatus(dependency.id, "CURRENT", 0) } returns dependency
        coEvery { repository.updateStatus(dependency.id, "OUTDATED", 1) } returns dependency.copy(
            status = DependencyStatus.OUTDATED,
            version = 2,
        )
        coEvery { repository.updateStatus(dependency.id, "INCOMPATIBLE", 9) } returns null
        coEvery {
            repository.updateResolvedVersion(dependency.id, resolvedVersionId, "OUTDATED", 2)
        } returns dependency.copy(
            resolvedProviderVersionId = resolvedVersionId,
            status = DependencyStatus.OUTDATED,
            version = 3,
        )
        coEvery { repository.updateResolvedVersion(dependency.id, null, "CURRENT", 10) } returns null
        coEvery { projectRepository.getById(dependency.consumerProjectId) } returns null
        coEvery { dispatcher.fireDependencyOutdated(any(), any(), any()) } throws IllegalStateException("dispatch")

        assertEquals(dependency, service.updateStatus(dependency.id, DependencyStatus.CURRENT, 0))
        assertEquals(DependencyStatus.OUTDATED, service.updateStatus(dependency.id, DependencyStatus.OUTDATED, 1).status)
        assertFailsWith<WorkOpsNotFoundException> {
            service.updateStatus(dependency.id, DependencyStatus.INCOMPATIBLE, 9)
        }

        val consumer = project(dependency.consumerProjectId)
        coEvery { projectRepository.getById(dependency.consumerProjectId) } returns consumer
        coEvery { programRepository.getById(consumer.programId) } returns null
        coEvery { dispatcher.fireDependencyOutdated(any(), any(), any()) } just Runs
        assertEquals(
            resolvedVersionId,
            service.updateResolvedVersion(
                dependency.id,
                resolvedVersionId,
                DependencyStatus.OUTDATED,
                2,
            ).resolvedProviderVersionId,
        )
        assertFailsWith<WorkOpsNotFoundException> {
            service.updateResolvedVersion(dependency.id, null, DependencyStatus.CURRENT, 10)
        }

        coEvery { dispatcher.fireDependencyOutdated(any(), any(), any()) } throws CancellationException("cancelled")
        assertFailsWith<CancellationException> {
            service.updateStatus(dependency.id, DependencyStatus.OUTDATED, 1)
        }
    }

    @Test
    fun `artifact registration lookups terminal states and removal delegate correctly`() = runTest {
        val repository = mockk<ArtifactPublicationRepository>()
        val service = ArtifactPublicationServiceImpl(repository, projectRepository, programRepository, dispatcher)
        val artifact = artifact()
        val input = RegisterArtifactInput(
            versionId = artifact.versionId,
            projectId = artifact.projectId,
            artifactType = ArtifactType.DOCKER,
            coordinates = "ghcr.io/bosca/server:6.0.0",
            repositoryUrl = "https://ghcr.io",
            checksumSha256 = "abc",
            externalUrl = "https://example.com/artifact",
            namespace = "bosca",
            environments = listOf("production"),
        )
        val registered = slot<ArtifactPublication>()
        coEvery { repository.getById(artifact.id) } returns artifact
        coEvery { repository.listByVersion(artifact.versionId) } returns listOf(artifact)
        coEvery { repository.listByProject(artifact.projectId) } returns listOf(artifact)
        coEvery { repository.add(capture(registered)) } returns artifact
        coEvery { repository.delete(artifact.id) } just Runs
        coEvery { repository.updateStatus(artifact.id, "FAILED", null, null, 1) } returns artifact.copy(
            status = PublicationStatus.FAILED,
        )
        coEvery { repository.updateStatus(artifact.id, "YANKED", null, null, 2) } returns artifact.copy(
            status = PublicationStatus.YANKED,
        )
        coEvery { repository.updateStatus(artifact.id, "FAILED", null, null, 8) } returns null
        coEvery { repository.updateStatus(artifact.id, "YANKED", null, null, 9) } returns null

        assertEquals(artifact, service.getById(artifact.id))
        assertEquals(listOf(artifact), service.listByVersion(artifact.versionId))
        assertEquals(listOf(artifact), service.listByProject(artifact.projectId))
        assertEquals(artifact, service.register(input))
        assertEquals(input.namespace, registered.captured.namespace)
        assertEquals(input.environments, registered.captured.environments)
        service.remove(artifact.id)
        assertEquals(PublicationStatus.FAILED, service.markFailed(artifact.id, 1).status)
        assertEquals(PublicationStatus.YANKED, service.yank(artifact.id, 2).status)
        assertFailsWith<WorkOpsNotFoundException> { service.markFailed(artifact.id, 8) }
        assertFailsWith<WorkOpsNotFoundException> { service.yank(artifact.id, 9) }
    }

    @Test
    fun `artifact registration is idempotent only for the same project version and type`() = runTest {
        val repository = mockk<ArtifactPublicationRepository>()
        val service = ArtifactPublicationServiceImpl(repository, projectRepository, programRepository, dispatcher)
        val artifact = artifact()
        val input = RegisterArtifactInput(
            artifact.versionId,
            artifact.projectId,
            artifact.artifactType,
            artifact.coordinates,
        )
        coEvery { repository.add(any()) } returns null
        coEvery { repository.getByCoordinates(artifact.coordinates) } returns artifact

        assertEquals(artifact, service.register(input))

        coEvery { repository.getByCoordinates(artifact.coordinates) } returns null
        assertFailsWith<IllegalStateException> { service.register(input) }

        listOf(
            artifact.copy(versionId = UUID.random()),
            artifact.copy(projectId = UUID.random()),
            artifact.copy(artifactType = ArtifactType.HELM),
        ).forEach { conflict ->
            coEvery { repository.getByCoordinates(artifact.coordinates) } returns conflict
            assertFailsWith<IllegalStateException> { service.register(input) }
        }
    }

    @Test
    fun `artifact publication covers missing rows scope fallbacks dispatch failure and cancellation`() = runTest {
        val repository = mockk<ArtifactPublicationRepository>()
        val service = ArtifactPublicationServiceImpl(repository, projectRepository, programRepository, dispatcher)
        val artifact = artifact()
        val principalId = UUID.random()
        coEvery { repository.updateStatus(artifact.id, "PUBLISHED", any(), principalId, 0) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.markPublished(artifact.id, principalId, 0) }

        coEvery { repository.updateStatus(artifact.id, "PUBLISHED", any(), principalId, 1) } returns artifact.copy(
            status = PublicationStatus.PUBLISHED,
        )
        coEvery { projectRepository.getById(artifact.projectId) } returns null
        coEvery { dispatcher.fireArtifactPublished(any(), any(), any(), any(), any()) } throws
                IllegalStateException("dispatch")
        assertEquals(PublicationStatus.PUBLISHED, service.markPublished(artifact.id, principalId, 1).status)

        val project = project(artifact.projectId)
        val program = Program(
            id = project.programId,
            portfolioId = UUID.random(),
            key = "PLAT",
            name = "Platform",
            ownerProfileId = UUID.random(),
        )
        coEvery { repository.updateStatus(artifact.id, "PUBLISHED", any(), principalId, 2) } returns artifact.copy(
            status = PublicationStatus.PUBLISHED,
        )
        coEvery { projectRepository.getById(artifact.projectId) } returns project
        coEvery { programRepository.getById(project.programId) } returns program
        coEvery { dispatcher.fireArtifactPublished(any(), any(), any(), any(), any()) } throws
                CancellationException("cancelled")
        assertFailsWith<CancellationException> { service.markPublished(artifact.id, principalId, 2) }
    }

    private fun dependency() = DependencyDeclaration(
        id = UUID.random(),
        consumerProjectId = UUID.random(),
        providerProjectId = UUID.random(),
        providerVersionConstraint = "^6.0.0",
        dependencyType = DependencyType.BUILD,
    )

    private fun artifact() = ArtifactPublication(
        id = UUID.random(),
        versionId = UUID.random(),
        projectId = UUID.random(),
        artifactType = ArtifactType.DOCKER,
        coordinates = "ghcr.io/bosca/server:6.0.0",
    )

    private fun project(id: UUID) = Project(
        id = id,
        programId = UUID.random(),
        key = "GIT",
        name = "Git",
        ownerProfileId = UUID.random(),
    )
}
