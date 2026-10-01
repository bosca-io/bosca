package bosca.ksp.generator.pipeline

import bosca.ksp.visitors.FoundPipelineNode
import bosca.ksp.visitors.FoundPipelineOutput
import bosca.ksp.visitors.FoundPipelineSlot
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.ClassName
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayOutputStream
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PipelineNodeIoGenerator] — verifies the generated `<Node>Serializer` codec across every
 * kind/type mapping arm: required vs optional slots, `ANY` pass-through, typed/untyped
 * `OBJECT`/`ARRAY`, the primitive and `UUID` kinds, single-input `inputs.first` vs multi-input
 * by-port access, and portless vs `onPort`-routed serialize functions. Content assertions (not
 * full golden files) so the test pins semantics, not KotlinPoet formatting.
 */
class PipelineNodeIoGeneratorTest {

    private val files = mutableMapOf<String, ByteArrayOutputStream>()
    private val codeGenerator = mockk<CodeGenerator>(relaxed = true) {
        every { createNewFile(any(), any(), any(), any()) } answers {
            files.getOrPut(thirdArg()) { ByteArrayOutputStream() }
        }
    }
    private val generator = PipelineNodeIoGenerator(codeGenerator)

    private val environment = ClassName("test.model", "Environment")
    private val release = ClassName("test.model", "Release")
    private val deployment = ClassName("test.model", "EnvironmentDeployment")

    @BeforeTest
    fun setup() = files.clear()

    private fun slot(
        name: String,
        kind: String,
        type: ClassName? = null,
        required: Boolean = true,
    ) = FoundPipelineSlot(name, kind, "", "", type, "", required)

    private fun output(
        name: String,
        kind: String,
        type: ClassName? = null,
        error: Boolean = false,
    ) = FoundPipelineOutput(name, kind, error, type, "", "")

    private fun node(
        name: String,
        label: String,
        inputs: List<FoundPipelineSlot> = emptyList(),
        outputs: List<FoundPipelineOutput> = emptyList(),
    ) = FoundPipelineNode(
        node = ClassName("test.pipeline", name),
        serialName = "test.${name.lowercase()}",
        category = "ACTION",
        label = label,
        description = "",
        inputs = inputs,
        outputs = outputs,
        settings = emptyList(),
        classDeclaration = mockk<KSClassDeclaration>(relaxed = true),
    )

    private fun generated(name: String): String {
        val stream = files["${name}Serializer"]
        assertTrue(stream != null, "expected a generated ${name}Serializer, got ${files.keys}")
        return stream.toString()
    }

    @Test
    fun `a multi-input node decodes each slot by port name at its physical type`() {
        generator.generate(
            listOf(
                node(
                    "PromoteNode", "Promote",
                    inputs = listOf(
                        slot("source", "OBJECT", environment),
                        slot("target", "OBJECT", environment),
                        slot("release", "OBJECT", release, required = false),
                        slot("after", "ANY", required = false),
                    ),
                    outputs = listOf(output("out", "ARRAY", deployment)),
                )
            )
        )
        val code = generated("PromoteNode")
        // Typed object slots decode by port name with the explicit serializer, failing when required.
        assertContains(
            code,
            """source = inputs["source"]?.decode(Environment.serializer(), context.json) ?: error("Promote: required input 'source' is missing")""",
        )
        // An optional slot decodes to null instead of failing.
        assertContains(code, """release = inputs["release"]?.decode(Release.serializer(), context.json),""")
        assertFalse("required input 'release'" in code)
        // An optional ANY slot passes the raw PipelineValue through, untouched.
        assertContains(code, """after = inputs["after"],""")
        // Inputs properties carry the physical types, nullable only when not required.
        assertContains(code, "public val source: Environment,")
        assertContains(code, "public val release: Release?,")
        assertContains(code, "public val after: PipelineValue?,")
        // The single output is the anonymous handle: portless, wrapped with ListSerializer.
        assertContains(
            code,
            "public fun serialize(`out`: List<EnvironmentDeployment>): PipelineValue = PipelineValue.of(`out`, ListSerializer(EnvironmentDeployment.serializer()))",
        )
        assertFalse("onPort" in code)
        // The lenient companion: every PartialInputs property is nullable and nothing throws.
        assertContains(code, "public fun deserializePartial(context: PipelineContext, inputs: NodeInputs): PartialInputs")
        assertContains(code, "public val source: Environment?,")
        val partial = code.substringAfter("fun deserializePartial")
        assertContains(partial, """source = inputs["source"]?.decode(Environment.serializer(), context.json),""")
        assertFalse("error(" in partial.substringBefore("public data class PartialInputs"))
    }

    @Test
    fun `a single-input node reads the sole inbound value whatever port it targeted`() {
        generator.generate(
            listOf(
                node(
                    "GetThingNode", "Get Thing",
                    inputs = listOf(slot("in", "UUID")),
                    outputs = listOf(output("out", "OBJECT", environment)),
                )
            )
        )
        val code = generated("GetThingNode")
        // Mirrors SlotValidator: a single-slot node matches inputs.first, not the port name.
        assertContains(
            code,
            """`in` = inputs.first?.decode(UUIDSerializer(), context.json) ?: error("Get Thing: required input 'in' is missing")""",
        )
        assertContains(code, "public val `in`: UUID,")
        assertContains(code, "PipelineValue.of(`out`, Environment.serializer())")
    }

    @Test
    fun `primitive and untyped slots map to their JSON physical types`() {
        generator.generate(
            listOf(
                node(
                    "MixedNode", "Mixed",
                    inputs = listOf(
                        slot("count", "INTEGER"),
                        slot("note", "STRING", required = false),
                        slot("ratio", "NUMBER"),
                        slot("flag", "BOOLEAN"),
                        slot("extra", "OBJECT"),
                        slot("items", "ARRAY"),
                        slot("gate", "ANY"),
                    ),
                )
            )
        )
        val code = generated("MixedNode")
        assertContains(code, "public val count: Long,")
        assertContains(code, "public val note: String?,")
        assertContains(code, "public val ratio: Double,")
        assertContains(code, "public val flag: Boolean,")
        assertContains(code, "public val extra: JsonObject,")
        assertContains(code, "public val items: JsonArray,")
        assertContains(code, "public val gate: PipelineValue,")
        assertContains(code, "count = inputs[\"count\"]?.decode(Long.serializer(), context.json)")
        assertContains(code, "note = inputs[\"note\"]?.decode(String.serializer(), context.json),")
        assertContains(code, "ratio = inputs[\"ratio\"]?.decode(Double.serializer(), context.json)")
        assertContains(code, "flag = inputs[\"flag\"]?.decode(Boolean.serializer(), context.json)")
        assertContains(code, "extra = inputs[\"extra\"]?.decode(JsonObject.serializer(), context.json)")
        assertContains(code, "items = inputs[\"items\"]?.decode(JsonArray.serializer(), context.json)")
        // A required ANY slot fails when absent rather than decoding.
        assertContains(code, """gate = inputs["gate"] ?: error("Mixed: required input 'gate' is missing"),""")
        // No outputs declared — no serialize function at all.
        assertFalse("fun serialize" in code)
    }

    @Test
    fun `multiple output ports each serialize onto their named port`() {
        generator.generate(
            listOf(
                node(
                    "WaitNode", "Wait",
                    inputs = listOf(slot("in", "UUID")),
                    outputs = listOf(
                        output("success", "UUID"),
                        output("failure", "ANY", error = true),
                        output("raw-data", "OBJECT"),
                    ),
                )
            )
        )
        val code = generated("WaitNode")
        assertContains(
            code,
            "public fun serializeSuccess(success: UUID): PipelineValue = PipelineValue.of(success, UUIDSerializer()).onPort(\"success\")",
        )
        // An ANY port passes the caller's PipelineValue through, just routed onto the port.
        assertContains(
            code,
            "public fun serializeFailure(failure: PipelineValue): PipelineValue = failure.onPort(\"failure\")",
        )
        // An untyped OBJECT port wraps plain JSON; the port name pascal-cases into the function name.
        assertContains(
            code,
            "public fun serializeRawData(`raw-data`: JsonObject): PipelineValue = PipelineValue.ofJson(`raw-data`).onPort(\"raw-data\")",
        )
    }

    @Test
    fun `a node with no declared slots generates nothing`() {
        generator.generate(listOf(node("BareNode", "Bare")))
        assertNull(files["BareNodeSerializer"])
        verify(exactly = 0) { codeGenerator.createNewFile(any(), any(), any(), any()) }
    }
}
