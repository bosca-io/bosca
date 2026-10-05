@file:OptIn(InternalDI::class)

package bosca.workops.pipeline

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.PipelineContext
import bosca.pipelines.DryRunTrace
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.PipelineValue
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.workops.model.artifact.ArtifactPublication
import bosca.workops.model.artifact.ArtifactType
import bosca.workops.model.environment.DeployInput
import bosca.workops.model.environment.Environment
import bosca.workops.model.environment.EnvironmentDeployment
import bosca.workops.model.release.Release
import bosca.workops.service.EnvironmentService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**[CreateEnvironmentDeploymentNode]: deploys a typed artifact publication into a typed environment. */
class CreateEnvironmentDeploymentNodeTest {

    private val json = Json {
        serializersModule = SerializersModule {
            contextual(UUID::class, UUIDSerializer())
            contextual(java.time.OffsetDateTime::class, OffsetDateTimeSerializer())
        }
        ignoreUnknownKeys = true
    }
    private val principalId = UUID.random()
    private val auth = mockk<AuthenticationContext> {
        every { principal() } returns mockk<AuthenticatedPrincipal> { every { id } returns principalId }
    }
    private val context = PipelineContext(auth, json)
    private val environments = mockk<EnvironmentService>(relaxed = true)

    private val publicationId = UUID.random()
    private val projectId = UUID.random()
    private val versionId = UUID.random()
    private val programId = UUID.random()
    private val envId = UUID.random()
    private val releaseId = UUID.random()
    private val deploymentId = UUID.random()

    private val artifact = ArtifactPublication(
        id = publicationId, versionId = versionId, projectId = projectId,
        artifactType = ArtifactType.DOCKER, coordinates = "img:1",
    )
    private val environment = Environment(id = envId, programId = programId, key = "production", name = "production")
    private val release = Release(id = releaseId, programId = programId, name = "1.0")

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<EnvironmentService> { environments }
        val pending = EnvironmentDeployment(id = deploymentId, environmentId = envId, projectId = projectId, versionId = versionId)
        coEvery { environments.createDeployment(any(), any()) } returns pending
        coEvery { environments.markDeployed(any(), any(), any()) } returns pending
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    private fun inputs(withRelease: Boolean = true) = NodeInputs(
        buildMap {
            put("artifact", PipelineValue.of(artifact, ArtifactPublication.serializer()))
            put("environment", PipelineValue.of(environment, Environment.serializer()))
            if (withRelease) put("release", PipelineValue.of(release, Release.serializer()))
        },
    )

    @Test
    fun `records a pending deployment stamped with the release, and does NOT mark it deployed`() = runTest {
        val captured = slot<DeployInput>()
        coEvery { environments.createDeployment(capture(captured), principalId) } returns
            EnvironmentDeployment(id = deploymentId, environmentId = envId, projectId = projectId, versionId = versionId)

        CreateEnvironmentDeploymentNode(id = "dep").run(context, inputs())

        assertEquals(envId, captured.captured.environmentId)
        assertEquals(projectId, captured.captured.projectId)
        assertEquals(versionId, captured.captured.versionId)
        assertEquals(publicationId, captured.captured.artifactPublicationId)
        assertEquals(releaseId, captured.captured.releaseId, "the release is stamped so its 'what's where' view sees it")
        // It records intent only — it does not know whether the artifact actually landed.
        coVerify(exactly = 0) { environments.markDeployed(any(), any(), any()) }
    }

    @Test
    fun `the release input is optional`() = runTest {
        val captured = slot<DeployInput>()
        coEvery { environments.createDeployment(capture(captured), principalId) } returns
            EnvironmentDeployment(id = deploymentId, environmentId = envId, projectId = projectId, versionId = versionId)

        CreateEnvironmentDeploymentNode(id = "dep").run(context, inputs(withRelease = false))

        assertEquals(null, captured.captured.releaseId)
    }

    @Test
    fun `fails when the artifact input is missing`() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            CreateEnvironmentDeploymentNode(id = "dep").run(
                context,
                NodeInputs(mapOf("environment" to PipelineValue.of(environment, Environment.serializer()))),
            )
        }
        assertTrue("artifact" in (e.message ?: ""), e.message)
    }

    @Test
    fun `fails when there is no authenticated principal`() = runTest {
        val anon = mockk<AuthenticationContext> { every { principal() } returns null }
        val e = assertFailsWith<IllegalStateException> {
            CreateEnvironmentDeploymentNode(id = "dep").run(PipelineContext(anon, json), inputs())
        }
        assertTrue("authenticated principal" in (e.message ?: ""), e.message)
    }

    @Test
    fun `dry run touches no service`() = runTest {
        CreateEnvironmentDeploymentNode(id = "dep").run(PipelineContext(auth, json, dryRun = true), inputs())
        coVerify(exactly = 0) { environments.createDeployment(any(), any()) }
        coVerify(exactly = 0) { environments.markDeployed(any(), any(), any()) }
    }

    @Test
    fun `dry run records complete and partially wired deployment intent`() = runTest {
        val completeTrace = DryRunTrace()
        CreateEnvironmentDeploymentNode(id = "dep").run(
            PipelineContext(auth, json, dryRun = true, trace = completeTrace),
            inputs(),
        )
        assertEquals(
            "production",
            completeTrace.actions.getValue("dep").jsonObject.getValue("environment").jsonPrimitive.content,
        )

        val partialTrace = DryRunTrace()
        CreateEnvironmentDeploymentNode(id = "partial").run(
            PipelineContext(auth, json, dryRun = true, trace = partialTrace),
            NodeInputs(emptyMap()),
        )
        assertEquals(
            "",
            partialTrace.actions.getValue("partial").jsonObject.getValue("environment").jsonPrimitive.content,
        )
        coVerify(exactly = 0) { environments.createDeployment(any(), any()) }
    }

    @Test
    fun `authenticated principal error uses the authored node name`() = runTest {
        val anonymous = mockk<AuthenticationContext> { every { principal() } returns null }

        val error = assertFailsWith<IllegalStateException> {
            CreateEnvironmentDeploymentNode(id = "dep", name = "Production Deployment").run(
                PipelineContext(anonymous, json),
                inputs(),
            )
        }

        assertTrue("Production Deployment" in error.message.orEmpty())
    }
}
