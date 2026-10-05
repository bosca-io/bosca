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
import bosca.workops.model.environment.Environment
import bosca.workops.model.environment.EnvironmentDeployment
import bosca.workops.model.release.Release
import bosca.workops.service.EnvironmentService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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

/**[CreateEnvironmentPromotionNode]: promotes a source environment's versions into a target. */
class CreateEnvironmentPromotionNodeTest {

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

    private val programId = UUID.random()
    private val sourceId = UUID.random()
    private val targetId = UUID.random()
    private val releaseId = UUID.random()

    private val source = Environment(id = sourceId, programId = programId, key = "staging", name = "staging")
    private val target = Environment(id = targetId, programId = programId, key = "production", name = "production")
    private val release = Release(id = releaseId, programId = programId, name = "1.0")

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<EnvironmentService> { environments }
        coEvery { environments.createPromotionDeployment(any(), any(), any(), any()) } returns emptyList()
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    private fun inputs(withRelease: Boolean = true) = NodeInputs(
        buildMap {
            put("source", PipelineValue.of(source, Environment.serializer()))
            put("target", PipelineValue.of(target, Environment.serializer()))
            if (withRelease) put("release", PipelineValue.of(release, Release.serializer()))
        },
    )

    @Test
    fun `promotes from the source environment into the target, stamping the release`() = runTest {
        CreateEnvironmentPromotionNode(id = "promo").run(context, inputs())
        coVerify(exactly = 1) { environments.createPromotionDeployment(sourceId, targetId, releaseId, principalId) }
    }

    @Test
    fun `the release input is optional`() = runTest {
        CreateEnvironmentPromotionNode(id = "promo").run(context, inputs(withRelease = false))
        coVerify(exactly = 1) { environments.createPromotionDeployment(sourceId, targetId, null, principalId) }
    }

    @Test
    fun `fails when the source environment is missing`() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            CreateEnvironmentPromotionNode(id = "promo").run(
                context,
                NodeInputs(mapOf("target" to PipelineValue.of(target, Environment.serializer()))),
            )
        }
        assertTrue("required input 'source'" in (e.message ?: ""), e.message)
        coVerify(exactly = 0) { environments.createPromotionDeployment(any(), any(), any(), any()) }
    }

    @Test
    fun `dry run touches no service`() = runTest {
        CreateEnvironmentPromotionNode(id = "promo").run(PipelineContext(auth, json, dryRun = true), inputs())
        coVerify(exactly = 0) { environments.createPromotionDeployment(any(), any(), any(), any()) }
    }

    @Test
    fun `dry run records complete and partially wired promotion intent`() = runTest {
        val completeTrace = DryRunTrace()
        CreateEnvironmentPromotionNode(id = "promo").run(
            PipelineContext(auth, json, dryRun = true, trace = completeTrace),
            inputs(),
        )
        val complete = completeTrace.actions.getValue("promo").jsonObject
        assertEquals("staging", complete.getValue("from").jsonPrimitive.content)
        assertEquals("production", complete.getValue("to").jsonPrimitive.content)

        val partialTrace = DryRunTrace()
        CreateEnvironmentPromotionNode(id = "partial").run(
            PipelineContext(auth, json, dryRun = true, trace = partialTrace),
            NodeInputs(emptyMap()),
        )
        val partial = partialTrace.actions.getValue("partial").jsonObject
        assertEquals("", partial.getValue("from").jsonPrimitive.content)
        assertEquals("", partial.getValue("to").jsonPrimitive.content)
        coVerify(exactly = 0) { environments.createPromotionDeployment(any(), any(), any(), any()) }
    }

    @Test
    fun `authenticated principal error names the authored node`() = runTest {
        val anonymous = mockk<AuthenticationContext> { every { principal() } returns null }

        val error = assertFailsWith<IllegalStateException> {
            CreateEnvironmentPromotionNode(id = "promo", name = "Production Promotion").run(
                PipelineContext(anonymous, json),
                inputs(),
            )
        }

        assertTrue("Production Promotion" in error.message.orEmpty())
    }
}
