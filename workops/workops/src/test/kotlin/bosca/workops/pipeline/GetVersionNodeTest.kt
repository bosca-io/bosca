@file:OptIn(InternalDI::class)

package bosca.workops.pipeline

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.PipelineValue
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.workops.model.version.Version
import bosca.workops.service.VersionService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [GetVersionNode]: loads a Version by id so a relay can read its name (`pv → Get Version → JSONata(name)`). */
class GetVersionNodeTest {

    private val versionService = mockk<VersionService>()
    private val json = Json { serializersModule = SerializersModule { contextual(UUIDSerializer()) } }
    private val context = PipelineContext(AuthenticationContext(null, null), json)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<VersionService> { versionService }
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    private fun version(id: UUID, name: String) =
        Version(id = id, projectId = UUID.random(), name = name, sequenceNumber = 1)

    @Test
    fun `resolves a version from a bare uuid input`() = runTest {
        val vid = UUID.random()
        coEvery { versionService.getById(vid) } returns version(vid, "v1.0.0")

        val inputs = NodeInputs(mapOf("in" to PipelineValue.ofJson(JsonPrimitive(vid.toString()))))
        val out = GetVersionNode(id = "getVersion").run(context, inputs)

        assertEquals("v1.0.0", (out.result?.value as Version).name)
    }

    @Test
    fun `resolves a version from a ReleaseProjectVersion's versionId`() = runTest {
        val vid = UUID.random()
        coEvery { versionService.getById(vid) } returns version(vid, "v2.1.0")

        val inputs = NodeInputs(
            mapOf(
                "in" to PipelineValue.ofJson(
                    buildJsonObject {
                        put("projectId", UUID.random().toString())
                        put("versionId", vid.toString())
                    },
                ),
            ),
        )
        val out = GetVersionNode(id = "getVersion").run(context, inputs)

        assertEquals("v2.1.0", (out.result?.value as Version).name)
    }

    @Test
    fun `resolves a version from an object's id`() = runTest {
        val vid = UUID.random()
        coEvery { versionService.getById(vid) } returns version(vid, "v3.0.0")

        val inputs = NodeInputs(mapOf("in" to PipelineValue.ofJson(buildJsonObject { put("id", vid.toString()) })))
        val out = GetVersionNode(id = "getVersion").run(context, inputs)

        assertEquals("v3.0.0", (out.result?.value as Version).name)
    }

    @Test
    fun `fails when the input carries no version id`() = runTest {
        val invalidInputs = listOf(
            NodeInputs(emptyMap()),
            NodeInputs(mapOf("in" to PipelineValue.ofJson(buildJsonObject { put("unrelated", "x") }))),
            NodeInputs(mapOf("in" to PipelineValue.ofJson(buildJsonObject { put("id", " ") }))),
            NodeInputs(mapOf("in" to PipelineValue.ofJson(buildJsonObject { put("versionId", buildJsonObject {}) }))),
            NodeInputs(mapOf("in" to PipelineValue.ofJson(buildJsonObject { put("versionId", JsonNull) }))),
        )
        invalidInputs.forEach { inputs ->
            val e = assertFailsWith<IllegalStateException> {
                GetVersionNode(id = "getVersion", name = "Get Version").run(context, inputs)
            }
            assertTrue("requires a version id" in (e.message ?: ""))
        }
    }

    @Test
    fun `fails when the version is not found`() = runTest {
        val vid = UUID.random()
        coEvery { versionService.getById(vid) } returns null

        val inputs = NodeInputs(mapOf("in" to PipelineValue.ofJson(JsonPrimitive(vid.toString()))))
        val e = assertFailsWith<IllegalStateException> {
            GetVersionNode(id = "getVersion").run(context, inputs)
        }
        assertTrue("not found" in (e.message ?: ""))
    }
}
