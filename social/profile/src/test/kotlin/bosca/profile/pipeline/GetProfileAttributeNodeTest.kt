@file:OptIn(ExperimentalUuidApi::class)

package bosca.profile.pipeline

import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.pipeline.GetProfileAttributeNode
import bosca.security.service.AuthenticationContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class GetProfileAttributeNodeTest {

    private val json = Json
    private val context = PipelineContext(AuthenticationContext(null, null), json)
    private val node = GetProfileAttributeNode(id = "n", typeId = "bosca.profiles.email")

    private fun attribute(typeId: String) = ProfileAttribute(
        profile = Uuid.random(),
        typeId = typeId,
        visibility = ProfileVisibility.USER,
        confidence = 100,
        priority = 0,
        source = "test",
    )

    private fun inputs(vararg attrs: ProfileAttribute) = NodeInputs(
        mapOf("in" to PipelineValue.of(attrs.toList(), ListSerializer(ProfileAttribute.serializer())))
    )

    @Test
    fun `selects the first attribute matching the typeId`() = runTest {
        val email = attribute("bosca.profiles.email")
        val hubspot = attribute("bosca.profiles.hubspot.id")
        val result = node.run(context, inputs(hubspot, email)) as NodeResult.Output
        val out = result.value ?: error("expected an output value")

        assertEquals("out", out.port)
        assertSame(email, out.value, "the matching ProfileAttribute should flow through, typed")
    }

    @Test
    fun `emits notFound when no attribute matches the typeId`() = runTest {
        val result = node.run(context, inputs(attribute("bosca.profiles.hubspot.id"))) as NodeResult.Output
        val out = result.value ?: error("expected an output value")

        assertEquals("notFound", out.port)
        val payload = out.encode(json).jsonObject
        assertEquals("bosca.profiles.email", payload["typeId"]?.jsonPrimitive?.content)
        assertEquals(false, payload["found"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `fails when the input is not a list of attributes`() = runTest {
        val notAList = NodeInputs(mapOf("in" to PipelineValue.ofJson(buildJsonObject { })))
        val error = assertFailsWith<IllegalStateException> { node.run(context, notAList) }
        assertEquals(true, error.message?.contains("requires a list of ProfileAttribute"))
    }

    @Test
    fun `fails when no typeId is configured`() = runTest {
        val unconfigured = GetProfileAttributeNode(id = "n", name = "Pick attribute", typeId = "")
        val error = assertFailsWith<IllegalArgumentException> { unconfigured.run(context, inputs(attribute("x"))) }
        assertEquals(true, error.message?.contains("requires a typeId"))
    }
}
