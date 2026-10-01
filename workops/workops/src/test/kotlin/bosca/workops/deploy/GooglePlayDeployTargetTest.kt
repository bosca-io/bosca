package bosca.workops.deploy

import bosca.artifacts.model.ArtifactRepository
import bosca.artifacts.model.ArtifactType
import bosca.artifacts.model.ArtifactVersion
import bosca.artifacts.model.ArtifactVersionBlob
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.artifacts.service.BlobStorageService
import bosca.pipelines.service.PipelineSecretService
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.store.pipelines.PlayDeployResult
import bosca.store.pipelines.PlayPublisher
import bosca.workops.model.environment.DeployInput
import bosca.workops.model.environment.EnvironmentDeployment
import bosca.workops.model.environment.EnvironmentDeploymentStatus
import bosca.workops.service.EnvironmentService
import bosca.workops.service.ReleaseNotesService
import bosca.workops.model.release.LocalizedReleaseNotes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GooglePlayDeployTargetTest {

    private val environments = mockk<EnvironmentService>()
    private val secrets = mockk<PipelineSecretService>()
    private val artifacts = mockk<ArtifactRepositoryService>()
    private val blobs = mockk<BlobStorageService>()
    private val publisher = mockk<PlayPublisher>()
    private val releaseNotes = mockk<ReleaseNotesService>()
    private val authentication = mockk<AuthenticationContext>(relaxed = true)

    private val principalId = UUID.random()
    private val environmentId = UUID.random()
    private val projectId = UUID.random()
    private val versionId = UUID.random()
    private val deploymentId = UUID.random()
    private val repositoryId = UUID.random()
    private val artifactVersionId = UUID.random()
    private val publicationId = UUID.random()
    private val allocationId = UUID.random()
    private val deploymentInput = slot<DeployInput>()
    private val bundlePath = slot<String>()

    private val pending = EnvironmentDeployment(
        id = deploymentId,
        environmentId = environmentId,
        projectId = projectId,
        targetKind = DeployTargetKind.GOOGLE_PLAY,
        versionId = versionId,
    )

    private val target = GooglePlayDeployTarget(environments, secrets, artifacts, blobs, publisher, releaseNotes)

    private fun config(rolloutPercentage: Double = 100.0): JsonElement = buildJsonObject {
        put("packageName", "io.example.app")
        put("track", "internal")
        put("serviceAccountSecret", "play-sa")
        put("rolloutPercentage", rolloutPercentage)
    }

    private fun artifact() = DeployArtifact(
        publicationId = publicationId,
        type = "raw",
        namespace = "bosca-raw",
        coordinate = "app:1.4.0",
    )

    private fun buildNumber(number: Long = 42, value: String = number.toString()) = DeployBuildNumber(
        allocationId = allocationId,
        number = number,
        value = value,
    )

    private fun request(config: JsonElement = config()) = DeployRequest(
        environmentId = environmentId,
        projectId = projectId,
        versionId = versionId,
        version = "1.4.0",
        config = config,
        deployedByPrincipalId = principalId,
        artifactPublicationId = publicationId,
        artifact = artifact(),
        buildNumber = buildNumber(),
    )

    @BeforeTest
    fun setup() {
        coEvery { releaseNotes.requireLocalized(versionId, listOf("en-US")) } returns listOf(
            LocalizedReleaseNotes("en-US", "Play notes", "App Store notes", "TestFlight notes"),
        )
        coEvery { environments.createDeployment(capture(deploymentInput), principalId) } returns pending
        coEvery { environments.markDeployed(deploymentId, principalId, any()) } returns
            pending.copy(status = EnvironmentDeploymentStatus.DEPLOYED)
        coEvery { environments.markFailed(deploymentId, any()) } returns
            pending.copy(status = EnvironmentDeploymentStatus.FAILED)
        coEvery { environments.markRolledBack(deploymentId, any()) } returns
            pending.copy(status = EnvironmentDeploymentStatus.ROLLED_BACK)
        coEvery { secrets.resolve("play-sa") } returns "service-account-json"
        coEvery { artifacts.findRepository("bosca-raw", "app", ArtifactType.RAW) } returns
            mockk<ArtifactRepository> { io.mockk.every { id } returns repositoryId }
        coEvery { artifacts.findVersion(repositoryId, "1.4.0") } returns
            mockk<ArtifactVersion> { io.mockk.every { id } returns artifactVersionId }
        coEvery { artifacts.getVersionBlobs(artifactVersionId) } returns listOf(
            ArtifactVersionBlob(
                versionId = artifactVersionId,
                digest = "sha256:bundle",
                role = "bundle",
                filename = "app.aab",
            ),
        )
        coEvery { blobs.getInputStream("sha256:bundle") } returns ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
        coEvery {
            publisher.deployBundleWithReleaseNotes(
                "service-account-json", "io.example.app", capture(bundlePath), "internal", 1.0, 42,
                mapOf("en-US" to "Play notes"),
            )
        } coAnswers {
            assertEquals(listOf<Byte>(1, 2, 3, 4), File(bundlePath.captured).readBytes().toList())
            PlayDeployResult(42, "internal", 1.0)
        }
        coEvery {
            publisher.setRollout("service-account-json", "io.example.app", "internal", any(), 42)
        } answers { PlayDeployResult(42, "internal", arg<Double>(3)) }
        coEvery {
            publisher.halt("service-account-json", "io.example.app", "internal", 42)
        } returns PlayDeployResult(42, "internal", 0.0)
    }

    @Test
    fun `deploy delegates the selected AAB and durable version code then cleans the temporary file`() = runTest {
        val outcome = target.deploy(request(), authentication)

        assertEquals(EnvironmentDeploymentStatus.DEPLOYED, outcome.status)
        assertEquals(allocationId, deploymentInput.captured.appBuildNumberAllocationId)
        assertEquals(publicationId, deploymentInput.captured.artifactPublicationId)
        assertTrue("@42" in outcome.reference && "internal" in outcome.reference)
        assertFalse(File(bundlePath.captured).exists())
        coVerify(exactly = 1) {
            publisher.deployBundleWithReleaseNotes("service-account-json", "io.example.app", any(), "internal", 1.0, 42, any())
        }
        coVerify(exactly = 1) { environments.markDeployed(deploymentId, principalId, any()) }
    }

    @Test
    fun `deploy converts release percentage once at the shared publisher boundary`() = runTest {
        coEvery {
            publisher.deployBundleWithReleaseNotes("service-account-json", "io.example.app", any(), "internal", 0.1, 42, any())
        } returns PlayDeployResult(42, "internal", 0.1)

        val outcome = target.deploy(request(config(10.0)), authentication)

        assertTrue("10%" in outcome.reference)
        coVerify { publisher.deployBundleWithReleaseNotes(any(), any(), any(), any(), 0.1, 42, any()) }
    }

    @Test
    fun `publisher failure records failed deployment and cancellation is not swallowed`() = runTest {
        coEvery { publisher.deployBundleWithReleaseNotes(any(), any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("upload rejected")
        assertFailsWith<IllegalStateException> { target.deploy(request(), authentication) }
        coVerify(exactly = 1) { environments.markFailed(deploymentId, any()) }

        coEvery { publisher.deployBundleWithReleaseNotes(any(), any(), any(), any(), any(), any(), any()) } throws
            CancellationException("cancelled")
        assertFailsWith<CancellationException> { target.deploy(request(), authentication) }
        coVerify(exactly = 1) { environments.markFailed(deploymentId, any()) }
    }

    @Test
    fun `rollout delegates the exact durable version code without changing deployment state`() = runTest {
        coEvery { environments.currentState(environmentId) } returns
            listOf(pending.copy(status = EnvironmentDeploymentStatus.DEPLOYED))

        val outcome = target.rollout(
            RolloutRequest(
                environmentId = environmentId,
                projectId = projectId,
                rolloutPercentage = 37.5,
                config = config(),
                deployedByPrincipalId = principalId,
                artifact = artifact(),
                buildNumber = buildNumber(),
            ),
            authentication,
        )

        assertEquals(deploymentId, outcome.deploymentId)
        assertEquals(EnvironmentDeploymentStatus.DEPLOYED, outcome.status)
        assertTrue("37.5%" in outcome.reference)
        coVerify { publisher.setRollout("service-account-json", "io.example.app", "internal", 0.375, 42) }
        coVerify(exactly = 0) { environments.markDeployed(any(), any(), any()) }
    }

    @Test
    fun `rollback halts the exact durable version and records rolled back`() = runTest {
        coEvery { environments.currentState(environmentId) } returns listOf(pending)

        val outcome = target.rollback(
            RollbackRequest(
                environmentId = environmentId,
                projectId = projectId,
                toRevision = 1,
                config = config(),
                deployedByPrincipalId = principalId,
                artifact = artifact(),
                buildNumber = buildNumber(),
            ),
            authentication,
        )

        assertEquals(EnvironmentDeploymentStatus.ROLLED_BACK, outcome.status)
        coVerify { publisher.halt("service-account-json", "io.example.app", "internal", 42) }
        coVerify { environments.markRolledBack(deploymentId, pending.version) }
    }

    @Test
    fun `config validation reports decoding identity build key credential and percentage errors`() {
        assertFailsWith<SerializationException> {
            Json.decodeFromString(GooglePlayTargetConfig.serializer(), "{}")
        }
        assertTrue(target.validateConfig(config()).isEmpty())
        assertTrue(target.validateConfig(Json.parseToJsonElement("[]")).single().contains("does not decode"))

        val problems = target.validateConfig(buildJsonObject {
            put("packageName", " ")
            put("buildNumberKey", "")
            put("serviceAccountSecret", " ")
            put("rolloutPercentage", 101)
        })

        assertTrue(problems.any { "packageName" in it })
        assertTrue(problems.any { "buildNumberKey" in it })
        assertTrue(problems.any { "serviceAccountSecret" in it })
        assertTrue(problems.any { "rolloutPercentage" in it })
        assertTrue(target.validateConfig(config(-0.1)).any { "rolloutPercentage" in it })
        assertTrue(target.validateConfig(config(Double.NaN)).any { "rolloutPercentage" in it })
        assertTrue(target.validateConfig(config(Double.POSITIVE_INFINITY)).any { "rolloutPercentage" in it })
        assertTrue(target.validateConfig(config(Double.NEGATIVE_INFINITY)).any { "rolloutPercentage" in it })
        assertTrue(
            target.validateConfig(
                buildJsonObject {
                    put("packageName", "io.example.app")
                    put("serviceAccountSecret", "play-sa")
                    put("releaseNotesLocales", kotlinx.serialization.json.JsonArray(emptyList()))
                },
            ).any { "releaseNotesLocales" in it },
        )
        assertTrue(
            target.validateConfig(
                Json.parseToJsonElement(
                    """{"packageName":"io.example.app","serviceAccountSecret":"play-sa","releaseNotesLocales":[" "]}""",
                ),
            ).any { "blanks" in it },
        )
    }

    @Test
    fun `deploy rejects invalid identity artifact and build number before publisher use`() = runTest {
        val invalid = listOf(
            request(buildJsonObject { put("packageName", " "); put("serviceAccountSecret", "play-sa") }),
            request(buildJsonObject { put("packageName", "io.example.app"); put("serviceAccountSecret", " ") }),
            request(config(-0.1)),
            request(
                Json.parseToJsonElement(
                    """{"packageName":"io.example.app","serviceAccountSecret":"play-sa","releaseNotesLocales":[]}""",
                ),
            ),
            request(
                Json.parseToJsonElement(
                    """{"packageName":"io.example.app","serviceAccountSecret":"play-sa","releaseNotesLocales":[" "]}""",
                ),
            ),
            request().copy(artifact = null),
            request().copy(artifact = artifact().copy(namespace = null)),
            request().copy(artifact = artifact().copy(namespace = " ")),
            request().copy(buildNumber = null),
            request().copy(buildNumber = buildNumber(0)),
            request().copy(buildNumber = buildNumber(2_100_000_001)),
            request().copy(buildNumber = buildNumber(value = "0042")),
        )

        invalid.forEach { assertFailsWith<Exception> { target.deploy(it, authentication) } }
        coVerify(exactly = 0) { publisher.deployBundleWithReleaseNotes(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `artifact resolution failures are explicit and mark the created deployment failed`() = runTest {
        listOf("invalid", ":1.4.0", "app:").forEach { coordinate ->
            assertTrue(
                "name:version" in assertFailsWith<IllegalArgumentException> {
                    target.deploy(request().copy(artifact = artifact().copy(coordinate = coordinate)), authentication)
                }.message.orEmpty(),
            )
        }

        coEvery { artifacts.findRepository("missing", "app", ArtifactType.RAW) } returns null
        assertTrue(
            "not found" in assertFailsWith<IllegalStateException> {
                target.deploy(request().copy(artifact = artifact().copy(namespace = "missing")), authentication)
            }.message.orEmpty(),
        )

        coEvery { artifacts.findVersion(repositoryId, "1.4.0") } returns null
        assertTrue("not published" in assertFailsWith<IllegalStateException> {
            target.deploy(request(), authentication)
        }.message.orEmpty())

        coEvery { artifacts.findVersion(repositoryId, "1.4.0") } returns
            mockk<ArtifactVersion> { io.mockk.every { id } returns artifactVersionId }
        coEvery { artifacts.getVersionBlobs(artifactVersionId) } returns listOf(
            ArtifactVersionBlob(artifactVersionId, "sha256:unknown", "bundle", null),
            ArtifactVersionBlob(artifactVersionId, "sha256:ipa", "bundle", "app.ipa"),
        )
        assertTrue("no .aab" in assertFailsWith<IllegalStateException> {
            target.deploy(request(), authentication)
        }.message.orEmpty())
        coVerify(exactly = 6) { environments.markFailed(deploymentId, any()) }
    }

    @Test
    fun `missing credential marks the deployment failed with the configured secret name`() = runTest {
        coEvery { secrets.resolve("play-sa") } returns null

        val failure = assertFailsWith<IllegalStateException> { target.deploy(request(), authentication) }

        assertTrue("play-sa" in failure.message.orEmpty())
        coVerify { environments.markFailed(deploymentId, any()) }
    }

    @Test
    fun `rollout and rollback require the selected artifact build and exact current target`() = runTest {
        val rollout = RolloutRequest(
            environmentId = environmentId,
            projectId = projectId,
            rolloutPercentage = 50.0,
            config = config(),
            deployedByPrincipalId = principalId,
            artifact = artifact(),
            buildNumber = buildNumber(),
        )
        coEvery { environments.currentState(environmentId) } returns listOf(
            pending.copy(projectId = UUID.random()),
            pending.copy(targetKind = DeployTargetKind.APP_STORE),
        )

        assertTrue("no current" in assertFailsWith<IllegalStateException> {
            target.rollout(rollout, authentication)
        }.message.orEmpty())
        assertFailsWith<IllegalArgumentException> {
            target.rollout(rollout.copy(rolloutPercentage = Double.POSITIVE_INFINITY), authentication)
        }
        assertFailsWith<IllegalStateException> { target.rollout(rollout.copy(artifact = null), authentication) }
        assertFailsWith<IllegalStateException> { target.rollout(rollout.copy(buildNumber = null), authentication) }
        assertFailsWith<IllegalStateException> {
            target.rollback(
                RollbackRequest(
                    environmentId, projectId, 1, config(), principalId,
                    artifact = null,
                    buildNumber = buildNumber(),
                ),
                authentication,
            )
        }
    }
}
