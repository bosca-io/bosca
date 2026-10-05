@file:OptIn(ExperimentalUuidApi::class)

package bosca.artifacts.ml.installer

import bosca.artifacts.model.ArtifactNamespace
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.security.model.Principal
import bosca.security.service.ApiTokenCreationResult
import bosca.security.service.ApiTokenInput
import bosca.security.service.ApiTokenService
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi

/**
 * Unit tests for [MlArtifactsInstaller]: it creates the non-public `model` namespace and mints a scoped
 * push + pull token for a dedicated principal on first run, and is idempotent thereafter.
 */
class MlArtifactsInstallerTest {

    private val security = mockk<SecurityService>(relaxed = true)
    private val tokens = mockk<ApiTokenService>(relaxed = true)
    private val artifacts = mockk<ArtifactRepositoryService>(relaxed = true)
    private val installer = MlArtifactsInstaller(security, tokens, artifacts)

    private val installation = mockk<PackageInstallation>(relaxed = true)
    private val version = mockk<PackageInstallationVersion>(relaxed = true)

    private fun tokenResult(raw: String) = ApiTokenCreationResult(mockk(relaxed = true), raw)

    @Test
    fun `fresh install creates the namespace, principal and two scoped tokens`() = runTest {
        val principalId = UUID.random()
        coEvery { artifacts.getNamespaceByName("model") } returns null
        coEvery { security.getPrincipalByIdentifier("ml-service") } returns null
        coEvery { security.addPrincipal(any(), any()) } returns mockk(relaxed = true) { every { id } returns principalId }
        val inputs = mutableListOf<ApiTokenInput>()
        coEvery { tokens.createToken(principalId, capture(inputs), principalId) } returns tokenResult("bsk_x")

        installer.install(installation, version)

        coVerify(exactly = 1) { artifacts.createNamespace("model", false) }
        coVerify(exactly = 1) { security.addPrincipal(any(), any()) }
        coVerify(exactly = 2) { tokens.createToken(principalId, any(), principalId) }
        // A push scope and a pull scope, both fine-grained to the `ml` type + `model` namespace.
        assertEquals(
            listOf(listOf("artifacts:ml:model/*:*:push"), listOf("artifacts:ml:model/*:*:pull")),
            inputs.map { it.scopes },
        )
    }

    @Test
    fun `idempotent when the service principal already exists`() = runTest {
        coEvery { artifacts.getNamespaceByName("model") } returns mockk<ArtifactNamespace>(relaxed = true)
        coEvery { security.getPrincipalByIdentifier("ml-service") } returns mockk<Principal>(relaxed = true)

        installer.install(installation, version)

        coVerify(exactly = 0) { artifacts.createNamespace(any(), any()) }
        coVerify(exactly = 0) { security.addPrincipal(any(), any()) }
        coVerify(exactly = 0) { tokens.createToken(any(), any(), any()) }
    }

    @Test
    fun `creates a missing namespace but still skips tokens when the principal exists`() = runTest {
        coEvery { artifacts.getNamespaceByName("model") } returns null
        coEvery { security.getPrincipalByIdentifier("ml-service") } returns mockk<Principal>(relaxed = true)

        installer.install(installation, version)

        coVerify(exactly = 1) { artifacts.createNamespace("model", false) }
        coVerify(exactly = 0) { tokens.createToken(any(), any(), any()) }
    }
}
