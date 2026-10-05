@file:OptIn(InternalDI::class)

package bosca.workops.pipeline

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.PipelineValue
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.workops.model.environment.Environment
import bosca.workops.model.environment.EnvironmentType
import bosca.workops.model.release.Release
import bosca.workops.service.EnvironmentService
import bosca.workops.service.EnvironmentTypeService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [GetEnvironmentNode]: resolves an [Environment] by **global environment type**
 * within a release's program at run time (names aren't portable across programs), with an optional
 * exact-name override for programs that have several environments of one type.
 */
class GetEnvironmentNodeTest {

    private val json = Json {
        serializersModule = SerializersModule {
            contextual(UUID::class, UUIDSerializer())
            contextual(java.time.OffsetDateTime::class, OffsetDateTimeSerializer())
        }
        ignoreUnknownKeys = true
    }
    private val context = PipelineContext(AuthenticationContext(null, null), json)
    private val environments = mockk<EnvironmentService>()
    private val types = mockk<EnvironmentTypeService>()

    private val programId = UUID.random()
    private val releaseId = UUID.random()
    private val envId = UUID.random()
    private val typeId = UUID.random()
    private val productionType = EnvironmentType(id = typeId, name = "production")

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<EnvironmentService> { environments }
        provides<EnvironmentTypeService> { types }
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    private fun releaseInput() = NodeInputs(
        mapOf("in" to PipelineValue.of(Release(id = releaseId, programId = programId, name = "1.0"), Release.serializer())),
    )

    @Test
    fun `resolves the program's single environment of the named type`() = runTest {
        coEvery { types.getByName("production") } returns productionType
        coEvery { environments.listByProgramAndType(programId, typeId) } returns
            listOf(Environment(id = envId, programId = programId, key = "prod-eu", name = "prod-eu", typeId = typeId))

        val out = GetEnvironmentNode(id = "env", environmentType = "production").run(context, releaseInput())
        val env = out.result?.encode(json)?.let { json.decodeFromJsonElement(Environment.serializer(), it) }
        assertEquals(envId, env?.id)
    }

    @Test
    fun `fails when the type is not in the global catalog`() = runTest {
        coEvery { types.getByName("qa") } returns null
        val e = assertFailsWith<IllegalStateException> {
            GetEnvironmentNode(id = "env", environmentType = "qa").run(context, releaseInput())
        }
        assertTrue("unknown environment type" in (e.message ?: ""), e.message)
    }

    @Test
    fun `fails when the release's program has no environment of the type`() = runTest {
        coEvery { types.getByName("production") } returns productionType
        coEvery { environments.listByProgramAndType(programId, typeId) } returns emptyList()
        val e = assertFailsWith<IllegalStateException> {
            GetEnvironmentNode(id = "env", environmentType = "production").run(context, releaseInput())
        }
        assertTrue("no 'production' environment" in (e.message ?: ""), e.message)
    }

    @Test
    fun `fails naming the candidates when the type is ambiguous in the program`() = runTest {
        coEvery { types.getByName("production") } returns productionType
        coEvery { environments.listByProgramAndType(programId, typeId) } returns listOf(
            Environment(id = envId, programId = programId, key = "prod-eu", name = "prod-eu", typeId = typeId),
            Environment(id = UUID.random(), programId = programId, key = "prod-us", name = "prod-us", typeId = typeId),
        )
        val e = assertFailsWith<IllegalStateException> {
            GetEnvironmentNode(id = "env", environmentType = "production").run(context, releaseInput())
        }
        assertTrue("prod-eu" in (e.message ?: "") && "prod-us" in (e.message ?: ""), e.message)
        assertTrue("exact name" in (e.message ?: ""), e.message)
    }

    @Test
    fun `an exact name overrides type resolution`() = runTest {
        coEvery { environments.getByProgramAndName(programId, "prod-us") } returns
            Environment(id = envId, programId = programId, key = "prod-us", name = "prod-us", typeId = typeId)

        // Type resolution must not even be consulted — no stubs for it exist here.
        val out = GetEnvironmentNode(id = "env", environmentType = "production", environmentName = "prod-us")
            .run(context, releaseInput())
        val env = out.result?.encode(json)?.let { json.decodeFromJsonElement(Environment.serializer(), it) }
        assertEquals(envId, env?.id)
    }

    @Test
    fun `fails when the exact-name override matches nothing in the program`() = runTest {
        coEvery { environments.getByProgramAndName(programId, "prod-us") } returns null
        val e = assertFailsWith<IllegalStateException> {
            GetEnvironmentNode(id = "env", environmentName = "prod-us").run(context, releaseInput())
        }
        assertTrue("no environment named" in (e.message ?: ""), e.message)
    }

    @Test
    fun `fails when both settings are blank`() = runTest {
        val e = assertFailsWith<IllegalStateException> { GetEnvironmentNode(id = "env").run(context, releaseInput()) }
        assertTrue("environment type" in (e.message ?: ""), e.message)
    }

    // ── Fallback chain ───────────────────────────────────────────────────────

    private val developmentTypeId = UUID.random()
    private val stagingTypeId = UUID.random()
    private val developmentType = EnvironmentType(id = developmentTypeId, name = "development")
    private val stagingType = EnvironmentType(id = stagingTypeId, name = "staging")

    @Test
    fun `falls back to the next type when the primary has no environment`() = runTest {
        coEvery { types.getByName("development") } returns developmentType
        coEvery { types.getByName("staging") } returns stagingType
        coEvery { environments.listByProgramAndType(programId, developmentTypeId) } returns emptyList()
        coEvery { environments.listByProgramAndType(programId, stagingTypeId) } returns
            listOf(Environment(id = envId, programId = programId, key = "staging", name = "Staging", typeId = stagingTypeId))

        val out = GetEnvironmentNode(
            id = "env", environmentType = "development",
            fallbackEnvironmentTypes = listOf("staging", "production"),
        ).run(context, releaseInput())
        val env = out.result?.encode(json)?.let { json.decodeFromJsonElement(Environment.serializer(), it) }
        assertEquals(envId, env?.id)
    }

    @Test
    fun `the primary type wins without consulting fallbacks`() = runTest {
        coEvery { types.getByName("development") } returns developmentType
        coEvery { environments.listByProgramAndType(programId, developmentTypeId) } returns
            listOf(Environment(id = envId, programId = programId, key = "dev", name = "Dev", typeId = developmentTypeId))

        // No stubs exist for staging/production — resolving them would throw a strict-mock error.
        val out = GetEnvironmentNode(
            id = "env", environmentType = "development",
            fallbackEnvironmentTypes = listOf("staging", "production"),
        ).run(context, releaseInput())
        val env = out.result?.encode(json)?.let { json.decodeFromJsonElement(Environment.serializer(), it) }
        assertEquals(envId, env?.id)
    }

    @Test
    fun `fails listing the tried chain when no type has an environment`() = runTest {
        coEvery { types.getByName("development") } returns developmentType
        coEvery { types.getByName("staging") } returns stagingType
        coEvery { types.getByName("production") } returns productionType
        coEvery { environments.listByProgramAndType(programId, any()) } returns emptyList()

        val e = assertFailsWith<IllegalStateException> {
            GetEnvironmentNode(
                id = "env", environmentType = "development",
                fallbackEnvironmentTypes = listOf("staging", "production"),
            ).run(context, releaseInput())
        }
        assertTrue("no 'development' environment" in (e.message ?: ""), e.message)
        assertTrue("also tried: staging, production" in (e.message ?: ""), e.message)
    }

    @Test
    fun `optional emits nothing when the whole chain is exhausted`() = runTest {
        coEvery { types.getByName("development") } returns developmentType
        coEvery { types.getByName("staging") } returns stagingType
        coEvery { environments.listByProgramAndType(programId, any()) } returns emptyList()

        val out = GetEnvironmentNode(
            id = "env", environmentType = "development",
            fallbackEnvironmentTypes = listOf("staging"), optional = true,
        ).run(context, releaseInput())
        assertEquals(null, out.result)
    }

    @Test
    fun `an unknown type mid-chain is skipped, not fatal`() = runTest {
        coEvery { types.getByName("development") } returns developmentType
        coEvery { types.getByName("qa") } returns null
        coEvery { types.getByName("production") } returns productionType
        coEvery { environments.listByProgramAndType(programId, developmentTypeId) } returns emptyList()
        coEvery { environments.listByProgramAndType(programId, typeId) } returns
            listOf(Environment(id = envId, programId = programId, key = "prod", name = "Prod", typeId = typeId))

        val out = GetEnvironmentNode(
            id = "env", environmentType = "development",
            fallbackEnvironmentTypes = listOf("qa", "production"),
        ).run(context, releaseInput())
        val env = out.result?.encode(json)?.let { json.decodeFromJsonElement(Environment.serializer(), it) }
        assertEquals(envId, env?.id)
    }

    @Test
    fun `an unknown type on an optional single-type node still emits nothing`() = runTest {
        coEvery { types.getByName("google-play") } returns null
        val out = GetEnvironmentNode(id = "env", environmentType = "google-play", optional = true)
            .run(context, releaseInput())
        assertEquals(null, out.result)
    }

    @Test
    fun `an ambiguous fallback rung fails rather than silently skipping it`() = runTest {
        coEvery { types.getByName("development") } returns developmentType
        coEvery { types.getByName("staging") } returns stagingType
        coEvery { environments.listByProgramAndType(programId, developmentTypeId) } returns emptyList()
        coEvery { environments.listByProgramAndType(programId, stagingTypeId) } returns listOf(
            Environment(id = envId, programId = programId, key = "stage-eu", name = "stage-eu", typeId = stagingTypeId),
            Environment(id = UUID.random(), programId = programId, key = "stage-us", name = "stage-us", typeId = stagingTypeId),
        )
        val e = assertFailsWith<IllegalStateException> {
            GetEnvironmentNode(
                id = "env", environmentType = "development",
                fallbackEnvironmentTypes = listOf("staging", "production"),
            ).run(context, releaseInput())
        }
        assertTrue("exact name" in (e.message ?: ""), e.message)
    }
}
