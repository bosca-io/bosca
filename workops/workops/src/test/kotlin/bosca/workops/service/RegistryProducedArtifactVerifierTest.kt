package bosca.workops.service

import bosca.artifacts.model.ArtifactTag
import bosca.artifacts.model.ArtifactType
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.git.model.ArtifactDefinition
import bosca.git.model.ArtifactRequirement
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * [RegistryProducedArtifactVerifier]: declared docker artifacts verify by TAG
 * (docker versions are digest-keyed), everything else by registry VERSION; store bundles/values/native
 * artifacts route to the RAW registry; unknown types can't be disproved and are skipped.
 */
class RegistryProducedArtifactVerifierTest {

    private val registry = mockk<ArtifactRepositoryService>()
    private val verifier = RegistryProducedArtifactVerifier(registry)

    private val repoId = UUID.random()
    private val versionId = UUID.random()

    private fun repo() = mockk<bosca.artifacts.model.ArtifactRepository> { every { id } returns repoId }

    @Test fun `completion resolves raw aliases and asks the artifacts service to announce the stored version`() = runTest {
        val jobIds = listOf(UUID.random(), UUID.random())
        val principalId = UUID.random()
        val commit = "1".repeat(40)
        coEvery { registry.findRepository("builds", "tool", ArtifactType.RAW) } returns repo()
        coEvery { registry.findVersion(repoId, "1.0") } returns mockk { every { id } returns versionId }
        coEvery { registry.completeVersion(versionId, commit, jobIds, principalId) } returns Unit
        verifier.completed(ArtifactDefinition("graalvm-native", "builds", "tool:1.0"), commit, jobIds, principalId)
        coVerify(exactly = 1) { registry.completeVersion(versionId, commit, jobIds, principalId) }
    }

    @Test fun `completion resolves docker tags to their stored manifest version`() = runTest {
        val digest = "sha256:" + "a".repeat(64)
        coEvery { registry.findRepository("docker", "tool", ArtifactType.DOCKER) } returns repo()
        coEvery { registry.findTag(repoId, "1.0") } returns mockk { every { manifestDigest } returns digest }
        coEvery { registry.findVersion(repoId, digest) } returns mockk { every { id } returns versionId }
        coEvery { registry.completeVersion(versionId, "commit", emptyList(), null) } returns Unit
        verifier.completed(ArtifactDefinition("docker", "docker", "tool:1.0"), "commit", emptyList(), null)
        coVerify { registry.completeVersion(versionId, "commit", emptyList(), null) }
    }

    @Test fun `completion skips unknown protocols and rejects malformed or missing stored artifacts`() = runTest {
        verifier.completed(ArtifactDefinition("custom", "builds", "tool:1.0"), "commit", emptyList(), null)
        for (coordinate in listOf("tool", ":1.0", "tool:")) {
            assertFailsWith<IllegalArgumentException> { verifier.completed(ArtifactDefinition("raw", "builds", coordinate), "commit", emptyList(), null) }
        }
        coEvery { registry.findRepository("builds", "tool", ArtifactType.RAW) } returns null
        assertFailsWith<NoSuchElementException> { verifier.completed(ArtifactDefinition("raw", "builds", "tool:1.0"), "commit", emptyList(), null) }
        coEvery { registry.findRepository("builds", "tool", ArtifactType.RAW) } returns repo()
        coEvery { registry.findVersion(repoId, "1.0") } returns null
        assertFailsWith<NoSuchElementException> { verifier.completed(ArtifactDefinition("raw", "builds", "tool:1.0"), "commit", emptyList(), null) }
        coEvery { registry.findRepository("docker", "tool", ArtifactType.DOCKER) } returns repo()
        coEvery { registry.findTag(repoId, "1.0") } returns null
        assertFailsWith<NoSuchElementException> { verifier.completed(ArtifactDefinition("docker", "docker", "tool:1.0"), "commit", emptyList(), null) }
        coVerify(exactly = 0) { registry.completeVersion(any(), any(), any(), any()) }
    }

    @Test fun `completion propagates registry failures and cancellation`() = runTest {
        coEvery { registry.findRepository("builds", "tool", ArtifactType.RAW) } returns repo()
        coEvery { registry.findVersion(repoId, "1.0") } returns mockk { every { id } returns versionId }
        val failure = IllegalStateException("unavailable")
        coEvery { registry.completeVersion(any(), any(), any(), any()) } throws failure
        assertSame(failure, assertFailsWith<IllegalStateException> { verifier.completed(ArtifactDefinition("raw", "builds", "tool:1.0"), "commit", emptyList(), null) })
        val cancelled = kotlinx.coroutines.CancellationException("cancelled")
        coEvery { registry.completeVersion(any(), any(), any(), any()) } throws cancelled
        assertSame(cancelled, assertFailsWith<kotlinx.coroutines.CancellationException> { verifier.completed(ArtifactDefinition("raw", "builds", "tool:1.0"), "commit", emptyList(), null) })
    }

    @Test
    fun `a docker artifact verifies by tag and a raw-routed one by version`() = runTest {
        coEvery { registry.findRepository("bosca-docker", "bosca", ArtifactType.DOCKER) } returns repo()
        coEvery { registry.findTag(repoId, "6.0.5") } returns mockk<ArtifactTag>()
        coEvery { registry.findRepository("bosca-helm", "bosca-values", ArtifactType.RAW) } returns repo()
        coEvery { registry.findVersion(repoId, "6.0.5") } returns mockk { every { id } returns versionId }

        val missing = verifier.missing(
            listOf(
                ArtifactDefinition("docker", "bosca-docker", "bosca:6.0.5"),
                ArtifactDefinition("helm-values", "bosca-helm", "bosca-values:6.0.5"),
            ),
        )

        assertEquals(emptyList(), missing)
    }

    @Test
    fun `an artifact whose repository or version is absent is reported missing`() = runTest {
        // Repository exists but the tag was never pushed.
        coEvery { registry.findRepository("bosca-docker", "bosca", ArtifactType.DOCKER) } returns repo()
        coEvery { registry.findTag(repoId, "6.0.5") } returns null
        // Repository was never created at all.
        coEvery { registry.findRepository("bosca-helm", "bosca-values", ArtifactType.RAW) } returns null
        coEvery { registry.findRepository("bosca-raw", "bundle", ArtifactType.RAW) } returns repo()
        coEvery { registry.findVersion(repoId, "6.0.5") } returns null

        val declared = listOf(
            ArtifactDefinition("docker", "bosca-docker", "bosca:6.0.5"),
            ArtifactDefinition("helm-values", "bosca-helm", "bosca-values:6.0.5"),
            ArtifactDefinition("raw", "bosca-raw", "bundle:6.0.5"),
        )

        assertEquals(declared, verifier.missing(declared))
    }

    @Test
    fun `an unknown type cannot be disproved and is skipped`() = runTest {
        val declared = listOf(ArtifactDefinition("tarball", "bosca-raw", "thing:1.0.0"))
        assertEquals(emptyList(), verifier.missing(declared))
    }

    @Test
    fun `a coordinate without a version segment is missing by definition`() = runTest {
        val declared = listOf(ArtifactDefinition("docker", "bosca-docker", "bosca"))
        assertEquals(declared, verifier.missing(declared))
    }

    @Test
    fun `all supported registry aliases reject malformed coordinates instead of being skipped`() = runTest {
        val supportedTypes = listOf(
            "docker", "helm", "maven", "npm", "ml", "raw", "helm-values",
            "android-aar", "android_aar", "ios-framework", "ios_framework",
            "graalvm-native", "graalvm_native", "wasm",
        )
        val malformed = supportedTypes.flatMap { type ->
            listOf(
                ArtifactDefinition(type, "registry", "artifact"),
                ArtifactDefinition(type, "registry", ":1.0"),
                ArtifactDefinition(type, "registry", "artifact:"),
            )
        }

        assertEquals(malformed, verifier.missing(malformed))
    }

    @Test
    fun `produced maven helm npm and ml artifacts resolve their native repository protocols`() = runTest {
        val declarations = listOf(
            ArtifactDefinition("maven", "maven", "io.bosca:core:1.0"),
            ArtifactDefinition("helm", "helm", "chart:1.0"),
            ArtifactDefinition("npm", "npm", "package:1.0"),
            ArtifactDefinition("ml", "ml", "model:1.0"),
        )
        coEvery { registry.findRepository("maven", "io.bosca.core", ArtifactType.MAVEN) } returns repo()
        coEvery { registry.findRepository("helm", "chart", ArtifactType.HELM) } returns repo()
        coEvery { registry.findRepository("npm", "package", ArtifactType.NPM) } returns repo()
        coEvery { registry.findRepository("ml", "model", ArtifactType.ML) } returns repo()
        coEvery { registry.findVersion(repoId, "1.0") } returns mockk { every { id } returns versionId }

        assertEquals(emptyList(), verifier.missing(declarations))
    }

    // ── RequiredArtifactVerifier ────────────────────────────────

    @Test
    fun `an exact requirement satisfies by version and a docker one by tag`() = runTest {
        coEvery { registry.findRepository("bosca-maven", "io.bosca.core-content", ArtifactType.MAVEN) } returns repo()
        coEvery { registry.findVersion(repoId, "6.0.9") } returns mockk { every { id } returns versionId }
        coEvery { registry.findRepository("bosca-docker", "core-api", ArtifactType.DOCKER) } returns repo()
        coEvery { registry.findTag(repoId, "6.0.9") } returns mockk<ArtifactTag>()

        val unsatisfied = verifier.unsatisfied(
            listOf(
                ArtifactRequirement("maven", "bosca-maven", "io.bosca:core-content:6.0.9"),
                ArtifactRequirement("docker", "bosca-docker", "core-api:6.0.9"),
            ),
        )

        assertEquals(emptyList(), unsatisfied)
    }

    @Test
    fun `a prefix requirement matches any published version with that prefix`() = runTest {
        coEvery { registry.findRepository("bosca-maven", "io.bosca.core-content", ArtifactType.MAVEN) } returns repo()
        coEvery { registry.findVersionByPrefix(repoId, "6.0") } returns mockk { every { id } returns versionId }

        val unsatisfied = verifier.unsatisfied(
            listOf(ArtifactRequirement("maven", "bosca-maven", "io.bosca:core-content:6.0.*")),
        )

        assertEquals(emptyList(), unsatisfied)
    }

    @Test
    fun `docker prefix requirements query tags with normalized dotted and undotted prefixes`() = runTest {
        coEvery { registry.findRepository("docker", "api", ArtifactType.DOCKER) } returns repo()
        coEvery { registry.findTagByPrefix(repoId, "6.1") } returns mockk<ArtifactTag>()
        coEvery { registry.findTagByPrefix(repoId, "latest") } returns mockk<ArtifactTag>()

        assertEquals(
            emptyList(),
            verifier.unsatisfied(
                listOf(
                    ArtifactRequirement("docker", "docker", "api:6.1.*"),
                    ArtifactRequirement("docker", "docker", "api:latest*"),
                ),
            ),
        )
    }

    @Test
    fun `prefix requirement is unsatisfied when repository or matching version is absent`() = runTest {
        val missingRepository = ArtifactRequirement("raw", "raw", "bundle:1.*")
        val missingVersion = ArtifactRequirement("raw", "raw", "other:2.*")
        coEvery { registry.findRepository("raw", "bundle", ArtifactType.RAW) } returns null
        coEvery { registry.findRepository("raw", "other", ArtifactType.RAW) } returns repo()
        coEvery { registry.findVersionByPrefix(repoId, "2") } returns null

        assertEquals(listOf(missingRepository, missingVersion), verifier.unsatisfied(listOf(missingRepository, missingVersion)))
    }

    @Test
    fun `a requirement whose provider never published is unsatisfied`() = runTest {
        coEvery { registry.findRepository("bosca-maven", "io.bosca.core-content", ArtifactType.MAVEN) } returns repo()
        coEvery { registry.findVersion(repoId, "6.0.9") } returns null

        val required = listOf(ArtifactRequirement("maven", "bosca-maven", "io.bosca:core-content:6.0.9"))
        assertEquals(required, verifier.unsatisfied(required))
    }

    @Test
    fun `an unknown requirement type is unsatisfied — a gate cannot wave through what it cannot check`() = runTest {
        val required = listOf(ArtifactRequirement("tarball", "bosca-raw", "thing:1.0.0"))
        assertEquals(required, verifier.unsatisfied(required))
    }

    @Test
    fun `a requirement coordinate without a version segment is unsatisfied by definition`() = runTest {
        val required = listOf(
            ArtifactRequirement("maven", "bosca-maven", "core-content"),
            ArtifactRequirement("maven", "bosca-maven", ":6.0.9"),
            ArtifactRequirement("maven", "bosca-maven", "core-content:"),
        )
        assertEquals(required, verifier.unsatisfied(required))
    }

    @Test
    fun `docker requirements are unsatisfied when exact and prefix tags are absent`() = runTest {
        coEvery { registry.findRepository("docker", "api", ArtifactType.DOCKER) } returns repo()
        coEvery { registry.findTag(repoId, "latest") } returns null
        coEvery { registry.findTagByPrefix(repoId, "6") } returns null
        val required = listOf(
            ArtifactRequirement("docker", "docker", "api:latest"),
            ArtifactRequirement("docker", "docker", "api:6.*"),
        )

        assertEquals(required, verifier.unsatisfied(required))
    }
}
