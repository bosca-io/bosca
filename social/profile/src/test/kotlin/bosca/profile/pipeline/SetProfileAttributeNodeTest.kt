@file:OptIn(InternalDI::class, ExperimentalUuidApi::class)

package bosca.profile.pipeline

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.attribute.model.ProfileAttributeInput
import bosca.profile.attribute.service.ProfileAttributeService
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.pipeline.SetProfileAttributeNode
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class SetProfileAttributeNodeTest {

    private val service = mockk<ProfileAttributeService>(relaxed = false)

    private val json = Json {
        serializersModule = SerializersModule { contextual(UUIDSerializer()) }
    }
    private val context = PipelineContext(AuthenticationContext(null, null), json)

    private val typeId = "bosca.profiles.plan"

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ProfileAttributeService> { service }
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    private fun inputs(
        profileId: UUID? = null,
        attributeId: UUID? = null,
        value: JsonObject? = null,
    ): NodeInputs {
        val map = buildMap {
            if (profileId != null) put("profile", PipelineValue.of(profileId, UUIDSerializer()))
            if (attributeId != null) put("attribute", PipelineValue.of(attributeId, UUIDSerializer()))
            if (value != null) put("value", PipelineValue.ofJson(value))
        }
        return NodeInputs(map)
    }

    private fun attribute(id: UUID, profileId: UUID, type: String = typeId) = ProfileAttribute(
        id = id,
        profile = profileId,
        typeId = type,
        visibility = ProfileVisibility.FRIENDS,
        confidence = 42,
        priority = 7,
        source = "import",
    )

    @Test
    fun `add inserts a new attribute on the profile input with a NIL id`() = runTest {
        val profileId = Uuid.random()
        val savedId = Uuid.random()
        val captured = slot<List<ProfileAttributeInput>>()
        coEvery { service.addAttributes(profileId, capture(captured)) } returns listOf(attribute(savedId, profileId))

        val node = SetProfileAttributeNode(id = "n1", operation = "add", typeId = typeId, visibility = ProfileVisibility.PUBLIC)
        val out = (node.run(context, inputs(profileId = profileId, value = buildJsonObject { put("plan", "pro") })) as NodeResult.Output).value ?: error("expected an output value")

        val input = captured.captured.single()
        assertEquals(UUID.NIL, input.id) // insert sentinel
        assertEquals(typeId, input.typeId)
        assertEquals(ProfileVisibility.PUBLIC, input.visibility) // node settings drive an add
        val result = out.value as JsonObject
        assertEquals("add", result["operation"]?.jsonPrimitive?.content)
        assertEquals(savedId.toString(), result["attributeId"]?.jsonPrimitive?.content)
        assertEquals(profileId.toString(), result["profileId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `update replaces the value of the attribute named by the attribute input, preserving its fields`() = runTest {
        val profileId = Uuid.random()
        val attributeId = Uuid.random()
        coEvery { service.getById(attributeId) } returns attribute(attributeId, profileId)
        val captured = slot<List<ProfileAttributeInput>>()
        coEvery { service.addAttributes(profileId, capture(captured)) } returns listOf(attribute(attributeId, profileId))

        // Settings that must NOT leak into an update — the existing attribute's fields win.
        val node = SetProfileAttributeNode(id = "n1", operation = "update", typeId = "ignored", visibility = ProfileVisibility.PUBLIC)
        val out = (node.run(context, inputs(attributeId = attributeId, value = buildJsonObject { put("plan", "enterprise") })) as NodeResult.Output).value ?: error("expected an output value")

        val input = captured.captured.single()
        assertEquals(attributeId, input.id) // edits the existing row (preserves verification)
        assertEquals(typeId, input.typeId) // preserved, not the 'ignored' setting
        assertEquals(ProfileVisibility.FRIENDS, input.visibility) // preserved, not the PUBLIC setting
        assertEquals(buildJsonObject { put("plan", "enterprise") }, input.attributes)
        assertEquals("update", (out.value as JsonObject)["operation"]?.jsonPrimitive?.content)
    }

    @Test
    fun `update fails clearly when the attribute does not exist`() = runTest {
        val attributeId = Uuid.random()
        coEvery { service.getById(attributeId) } returns null

        val node = SetProfileAttributeNode(id = "n1", name = "Set plan", operation = "update")
        val e = assertFailsWith<IllegalStateException> {
            node.run(context, inputs(attributeId = attributeId, value = buildJsonObject { put("plan", "pro") }))
        }
        assertTrue(e.message?.contains("Set plan") == true)
        assertTrue(e.message?.contains("no attribute") == true)
        coVerify(exactly = 0) { service.addAttributes(any(), any()) }
    }

    @Test
    fun `delete removes the attribute named by the attribute input`() = runTest {
        val profileId = Uuid.random()
        val attributeId = Uuid.random()
        coEvery { service.deleteAttribute(attributeId) } returns profileId

        val node = SetProfileAttributeNode(id = "n1", operation = "delete")
        val out = (node.run(context, inputs(attributeId = attributeId)) as NodeResult.Output).value ?: error("expected an output value")

        coVerify(exactly = 1) { service.deleteAttribute(attributeId) }
        val result = out.value as JsonObject
        assertEquals("delete", result["operation"]?.jsonPrimitive?.content)
        assertEquals(true, result["found"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(profileId.toString(), result["profileId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `delete reports found false when nothing matched`() = runTest {
        val attributeId = Uuid.random()
        coEvery { service.deleteAttribute(attributeId) } returns UUID.NIL

        val node = SetProfileAttributeNode(id = "n1", operation = "delete")
        val out = (node.run(context, inputs(attributeId = attributeId)) as NodeResult.Output).value ?: error("expected an output value")

        assertEquals(false, (out.value as JsonObject)["found"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `add without a profile input fails`() = runTest {
        val node = SetProfileAttributeNode(id = "n1", operation = "add", typeId = typeId)
        val e = assertFailsWith<IllegalStateException> {
            node.run(context, inputs(value = buildJsonObject { put("plan", "pro") }))
        }
        assertTrue(e.message?.contains("requires a profile UUID") == true)
    }

    @Test
    fun `add without a value input fails`() = runTest {
        val node = SetProfileAttributeNode(id = "n1", operation = "add", typeId = typeId)
        val e = assertFailsWith<IllegalStateException> {
            node.run(context, inputs(profileId = Uuid.random()))
        }
        assertTrue(e.message?.contains("requires a 'value' input") == true)
    }

    @Test
    fun `add with a blank typeId fails before touching the service`() = runTest {
        val node = SetProfileAttributeNode(id = "n1", operation = "add", typeId = "")
        assertFailsWith<IllegalArgumentException> {
            node.run(context, inputs(profileId = Uuid.random(), value = buildJsonObject { put("plan", "pro") }))
        }
        coVerify(exactly = 0) { service.addAttributes(any(), any()) }
    }

    @Test
    fun `update without an attribute input fails`() = runTest {
        val node = SetProfileAttributeNode(id = "n1", operation = "update")
        val e = assertFailsWith<IllegalStateException> {
            node.run(context, inputs(value = buildJsonObject { put("plan", "pro") }))
        }
        assertTrue(e.message?.contains("requires an attribute UUID") == true)
    }

    @Test
    fun `an unknown operation fails`() = runTest {
        val node = SetProfileAttributeNode(id = "n1", operation = "frobnicate")
        val e = assertFailsWith<IllegalArgumentException> {
            node.run(context, inputs(profileId = Uuid.random()))
        }
        assertTrue(e.message?.contains("unknown operation") == true)
    }

    @Test
    fun `a dry run records the would-be change and mutates nothing`() = runTest {
        val trace = DryRunTrace()
        val dryContext = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)

        val node = SetProfileAttributeNode(id = "n1", operation = "delete")
        val out = (node.run(dryContext, inputs(attributeId = Uuid.random())) as NodeResult.Output).value ?: error("expected an output value")

        val recorded = trace.actions["n1"] as JsonObject
        assertEquals("setProfileAttribute", recorded["action"]?.jsonPrimitive?.content)
        assertEquals("delete", recorded["operation"]?.jsonPrimitive?.content)
        assertEquals(true, (out.value as JsonObject)["dryRun"]?.jsonPrimitive?.content?.toBoolean())
        coVerify(exactly = 0) { service.deleteAttribute(any<UUID>()) }
        coVerify(exactly = 0) { service.addAttributes(any(), any()) }
    }
}
