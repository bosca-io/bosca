@file:OptIn(InternalDI::class)

package bosca.workops.pipeline

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.PipelineValue
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.workops.model.environment.EnvironmentDeployment
import bosca.workops.model.environment.EnvironmentDeploymentStatus
import bosca.workops.service.EnvironmentService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**[MarkDeploymentDeployedNode]: confirms a pending deployment landed (→ DEPLOYED). */
class MarkDeploymentDeployedNodeTest {

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

    private val deploymentId = UUID.random()
    private val envId = UUID.random()
    private val projectId = UUID.random()
    private val versionId = UUID.random()

    private val pending = EnvironmentDeployment(
        id = deploymentId, environmentId = envId, projectId = projectId, versionId = versionId, version = 3,
    )

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<EnvironmentService> { environments }
        coEvery { environments.markDeployed(any(), any(), any()) } returns pending.copy(status = EnvironmentDeploymentStatus.DEPLOYED)
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    private fun input() = NodeInputs(mapOf("in" to PipelineValue.of(pending, EnvironmentDeployment.serializer())))

    @Test
    fun `marks the inbound deployment DEPLOYED, carrying its id and optimistic version`() = runTest {
        MarkDeploymentDeployedNode(id = "confirm").run(context, input())
        coVerify(exactly = 1) { environments.markDeployed(deploymentId, principalId, 3L) }
    }

    @Test
    fun `fails when there is no authenticated principal`() = runTest {
        val anon = mockk<AuthenticationContext> { every { principal() } returns null }
        val e = assertFailsWith<IllegalStateException> {
            MarkDeploymentDeployedNode(id = "confirm").run(PipelineContext(anon, json), input())
        }
        assertTrue("authenticated principal" in (e.message ?: ""), e.message)
    }

    @Test
    fun `dry run touches no service`() = runTest {
        MarkDeploymentDeployedNode(id = "confirm").run(PipelineContext(auth, json, dryRun = true), input())
        coVerify(exactly = 0) { environments.markDeployed(any(), any(), any()) }
    }
}
