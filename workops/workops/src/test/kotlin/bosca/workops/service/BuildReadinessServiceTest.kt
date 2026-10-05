package bosca.workops.service

import bosca.serialization.UUID
import bosca.workops.model.artifact.ArtifactPublication
import bosca.workops.model.artifact.ArtifactType
import bosca.workops.model.artifact.PublicationStatus
import bosca.workops.model.compatibility.CompatibilityStatus
import bosca.workops.model.compatibility.CompatibilityTestResult
import bosca.workops.model.dependency.BuildBlockerType
import bosca.workops.model.dependency.DependencyDeclaration
import bosca.workops.model.dependency.DependencyStatus
import bosca.workops.model.dependency.DependencyType
import bosca.workops.repository.ArtifactPublicationRepository
import bosca.workops.repository.CompatibilityTestResultRepository
import bosca.workops.repository.DependencyDeclarationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildReadinessServiceTest {

    private val dependencies = mockk<DependencyDeclarationRepository>()
    private val compatibility = mockk<CompatibilityTestResultRepository>()
    private val artifacts = mockk<ArtifactPublicationRepository>()
    private val service = BuildReadinessServiceImpl(dependencies, compatibility, artifacts)

    @Test
    fun `readiness reports incompatible failed pending and missing-artifact blockers`() = runTest {
        val projectId = UUID.random()
        val consumerVersion = UUID.random()
        val incompatible = dependency(projectId, status = DependencyStatus.INCOMPATIBLE, coordinates = "io.bosca:core")
        val failed = dependency(
            projectId,
            status = DependencyStatus.OUTDATED,
            consumerVersionId = consumerVersion,
            resolvedVersionId = UUID.random(),
            coordinates = "io.bosca:failed",
        )
        val pending = dependency(
            projectId,
            status = DependencyStatus.OUTDATED,
            consumerVersionId = consumerVersion,
            resolvedVersionId = UUID.random(),
            coordinates = "io.bosca:pending",
        )
        val missingArtifact = dependency(
            projectId,
            status = DependencyStatus.CURRENT,
            resolvedVersionId = UUID.random(),
        )
        val failedResult = compatibilityResult(
            consumerProjectId = projectId,
            consumerVersionId = consumerVersion,
            providerProjectId = failed.providerProjectId,
            providerVersionId = failed.resolvedProviderVersionId ?: error("missing version"),
            status = CompatibilityStatus.FAILED,
        )
        val unrelated = compatibilityResult(
            consumerProjectId = projectId,
            consumerVersionId = consumerVersion,
            providerProjectId = UUID.random(),
            providerVersionId = UUID.random(),
            status = CompatibilityStatus.PASSED,
        )
        val passedForFailedProvider = compatibilityResult(
            consumerProjectId = projectId,
            consumerVersionId = consumerVersion,
            providerProjectId = failed.providerProjectId,
            providerVersionId = failed.resolvedProviderVersionId ?: error("missing version"),
            status = CompatibilityStatus.PASSED,
        )
        coEvery { dependencies.listByConsumer(projectId) } returns listOf(
            incompatible,
            failed,
            pending,
            missingArtifact,
        )
        coEvery { compatibility.listByConsumerVersion(projectId, consumerVersion) } returnsMany listOf(
            listOf(unrelated, passedForFailedProvider, failedResult),
            listOf(unrelated),
        )
        coEvery { artifacts.listByVersion(missingArtifact.resolvedProviderVersionId!!) } returns listOf(
            publication(missingArtifact.resolvedProviderVersionId!!, PublicationStatus.PENDING),
        )

        val readiness = service.check(projectId)

        assertFalse(readiness.ready)
        assertEquals(
            listOf(
                BuildBlockerType.INCOMPATIBLE_DEPENDENCY,
                BuildBlockerType.COMPILE_CHECK_FAILED,
                BuildBlockerType.COMPILE_CHECK_PENDING,
                BuildBlockerType.PROVIDER_ARTIFACTS_MISSING,
            ),
            readiness.blockers.map { it.blockerType },
        )
        assertEquals(failedResult.id, readiness.blockers[1].compatibilityTestId)
        assertTrue(readiness.blockers.first().description.contains("io.bosca:core"))
        assertTrue(readiness.blockers.last().description.contains(missingArtifact.providerProjectId.toString()))
    }

    @Test
    fun `readiness ignores unresolved checks and accepts passed compatibility and published artifacts`() = runTest {
        val projectId = UUID.random()
        val consumerVersion = UUID.random()
        val noResolvedVersion = dependency(projectId, status = DependencyStatus.OUTDATED)
        val noConsumerVersion = dependency(
            projectId,
            status = DependencyStatus.OUTDATED,
            resolvedVersionId = UUID.random(),
        )
        val passed = dependency(
            projectId,
            status = DependencyStatus.OUTDATED,
            consumerVersionId = consumerVersion,
            resolvedVersionId = UUID.random(),
        )
        val unresolvedCurrent = dependency(projectId, status = DependencyStatus.CURRENT)
        val published = dependency(
            projectId,
            status = DependencyStatus.CURRENT,
            resolvedVersionId = UUID.random(),
        )
        coEvery { dependencies.listByConsumer(projectId) } returns listOf(
            noResolvedVersion,
            noConsumerVersion,
            passed,
            unresolvedCurrent,
            published,
        )
        coEvery { compatibility.listByConsumerVersion(projectId, consumerVersion) } returns listOf(
            compatibilityResult(
                projectId,
                consumerVersion,
                passed.providerProjectId,
                passed.resolvedProviderVersionId ?: error("missing version"),
                CompatibilityStatus.PASSED,
            ),
        )
        coEvery { artifacts.listByVersion(published.resolvedProviderVersionId!!) } returns listOf(
            publication(published.resolvedProviderVersionId!!, PublicationStatus.PUBLISHED),
        )

        val readiness = service.check(projectId)

        assertTrue(readiness.ready)
        assertTrue(readiness.blockers.isEmpty())
        coVerify(exactly = 1) { compatibility.listByConsumerVersion(any(), any()) }
        coVerify(exactly = 1) { artifacts.listByVersion(any()) }
    }

    private fun dependency(
        consumerProjectId: UUID,
        status: DependencyStatus,
        consumerVersionId: UUID? = null,
        resolvedVersionId: UUID? = null,
        coordinates: String? = null,
    ) = DependencyDeclaration(
        consumerProjectId = consumerProjectId,
        consumerVersionId = consumerVersionId,
        providerProjectId = UUID.random(),
        providerVersionConstraint = "*",
        resolvedProviderVersionId = resolvedVersionId,
        dependencyType = DependencyType.BUILD,
        artifactCoordinates = coordinates,
        status = status,
    )

    private fun compatibilityResult(
        consumerProjectId: UUID,
        consumerVersionId: UUID,
        providerProjectId: UUID,
        providerVersionId: UUID,
        status: CompatibilityStatus,
    ) = CompatibilityTestResult(
        id = UUID.random(),
        consumerProjectId = consumerProjectId,
        consumerVersionId = consumerVersionId,
        providerProjectId = providerProjectId,
        providerVersionId = providerVersionId,
        testSuite = "compile",
        status = status,
    )

    private fun publication(versionId: UUID, status: PublicationStatus) = ArtifactPublication(
        id = UUID.random(),
        versionId = versionId,
        projectId = UUID.random(),
        artifactType = ArtifactType.MAVEN,
        coordinates = "io.bosca:core:1",
        status = status,
    )
}
