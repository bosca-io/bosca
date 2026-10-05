package bosca.pipelines.builtin

import bosca.communications.model.BmlMessageHostedProject
import bosca.communications.model.BmlMessageTemplateInfo
import bosca.communications.service.BmlMessageTemplateRendererService
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.model.PipelineNamedShape
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.ShapeField
import bosca.pipelines.service.PipelineShapeService
import bosca.security.service.AuthenticationContext
import bosca.serialization.SerializerCache
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Registered under its custom serial name — proves the [SerializerCache] name-index path. */
@Serializable
@SerialName("test.cast.User")
data class CastTestUser(val id: String, val roles: List<String> = emptyList())

/** Top-level and unregistered — its serial name is the FQCN, proving the Class.forName fallback path. */
@Serializable
data class CastTestFallback(val value: String)

private interface CastTestEvent

@Serializable
@SerialName("test.cast.Event")
private data class CastTestConcreteEvent(val id: String) : CastTestEvent

/** A polymorphic supertype whose encoding carries a `type` discriminator — the interface→concrete case. */
@Serializable
sealed class CastTestActor

@Serializable
@SerialName("test.cast.ServiceActor")
data class CastTestServiceActor(val id: String) : CastTestActor()

class CastNodeTest {

    private val shapeService = mockk<PipelineShapeService>()
    private val emailRenderer = mockk<BmlMessageTemplateRendererService>()

    // Mirrors the platform's global Json (BoscaApplication): lenient with unknown keys ignored, so a
    // polymorphic discriminator survives an interface→concrete cast exactly as it does at run time.
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val context get() = PipelineContext(AuthenticationContext(null, null), json)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<PipelineShapeService> { shapeService }
        provides<BmlMessageTemplateRendererService> { emailRenderer }
        SerializerCache.register(CastTestUser::class.java, CastTestUser.serializer())
        SerializerCache.register(CastTestServiceActor::class.java, CastTestServiceActor.serializer())
        SerializerCache.register(
            CastTestConcreteEvent::class.java,
            CastTestConcreteEvent.serializer(),
            CastTestEvent::class.java,
        )
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    private suspend fun cast(target: String, value: PipelineValue): PipelineValue? =
        CastNode(id = "c", outputType = target).run(context, NodeInputs(mapOf("in" to value))).result

    private suspend fun failure(target: String, value: PipelineValue): String {
        val e = assertFailsWith<IllegalStateException> {
            CastNode(id = "c", name = "Reinterpret", outputType = target)
                .run(context, NodeInputs(mapOf("in" to value)))
        }
        val message = e.message ?: ""
        assertTrue("Reinterpret" in message, message)
        return message
    }

    // ---- casting to a catalogued type ----

    @Test
    fun `casts plain JSON to a typed object`() = runTest {
        val out = cast("test.cast.User", PipelineValue.ofJson(buildJsonObject { put("id", "u1") }))
        assertEquals(CastTestUser("u1"), out?.value)
        assertEquals("test.cast.User", out?.typeName)
    }

    @Test
    fun `the cast value feeds a type-pinned slot because it carries the target serializer`() = runTest {
        val out = cast(
            "test.cast.User",
            PipelineValue.ofJson(buildJsonObject { put("id", "u1") }),
        )
        // typeName is what the runtime SlotValidator pins on — the cast made it the target type.
        assertEquals("test.cast.User", out?.typeName)
    }

    @Test
    fun `casts a supertype-typed value to a concrete implementation`() = runTest {
        // Encoded via the sealed supertype's serializer, the JSON carries a `type` discriminator —
        // the concrete decode tolerates it exactly as the platform's lenient Json does at run time.
        val actor: CastTestActor = CastTestServiceActor("svc-1")
        val out = cast("test.cast.ServiceActor", PipelineValue.of(actor, CastTestActor.serializer()))
        assertEquals(CastTestServiceActor("svc-1"), out?.value)
        assertEquals("test.cast.ServiceActor", out?.typeName)
    }

    @Test
    fun `casts a concrete event to a supported non-serializable interface`() = runTest {
        val value = PipelineValue.of(CastTestConcreteEvent("e1"), CastTestConcreteEvent.serializer())

        val out = cast(CastTestEvent::class.java.name, value)

        assertSame(value.value, out?.value)
        assertEquals(CastTestEvent::class.java.name, out?.typeName)
    }

    @Test
    fun `casts an interface-declared event back to its concrete type`() = runTest {
        val concrete = CastTestConcreteEvent("e1")
        val interfaceValue = PipelineValue.of(concrete, CastTestConcreteEvent.serializer())
            .declaredAs(CastTestEvent::class.java.name)

        val out = cast("test.cast.Event", interfaceValue)

        assertSame(concrete, out?.value)
        assertEquals("test.cast.Event", out?.typeName)
    }

    @Test
    fun `rejects an object that does not implement a catalogued interface`() = runTest {
        val message = failure(
            CastTestEvent::class.java.name,
            PipelineValue.of(CastTestUser("u1"), CastTestUser.serializer()),
        )
        assertTrue("does not implement" in message, message)
    }

    @Test
    fun `a value already of the target type passes straight through`() = runTest {
        val value = PipelineValue.of(CastTestUser("u1"), CastTestUser.serializer())
        assertSame(value, cast("test.cast.User", value))
    }

    @Test
    fun `resolves an unregistered type by its fully-qualified serial name`() = runTest {
        val target = CastTestFallback.serializer().descriptor.serialName
        val out = cast(target, PipelineValue.ofJson(buildJsonObject { put("value", "x") }))
        assertEquals(CastTestFallback("x"), out?.value)
    }

    @Test
    fun `a value that does not fit the target type fails the cast`() = runTest {
        // `id` is required by CastTestUser and absent here — the graph's ClassCastException.
        val message = failure("test.cast.User", PipelineValue.ofJson(buildJsonObject { put("name", "nope") }))
        assertTrue("cannot cast" in message && "User" in message, message)
    }

    @Test
    fun `an unknown type fails with a clear message`() = runTest {
        val message = failure("com.example.DoesNotExist", PipelineValue.ofJson(buildJsonObject { }))
        assertTrue("unknown type" in message, message)
    }

    @Test
    fun `null cannot be cast`() = runTest {
        assertTrue("cannot cast null" in failure("test.cast.User", PipelineValue.ofJson(JsonNull)))
    }

    @Test
    fun `a nullable typed value cannot be cast to a non-null target`() = runTest {
        val nullable = PipelineValue.of<String?>(null, String.serializer().nullable)
        assertTrue("cannot cast null" in failure("test.cast.User", nullable))
    }

    @Test
    fun `cast failures use the node id when no name is configured`() = runTest {
        val failure = assertFailsWith<IllegalStateException> {
            CastNode(id = "cast-id", outputType = "test.cast.User").run(
                context,
                NodeInputs(mapOf("in" to PipelineValue.ofJson(buildJsonObject { put("name", "missing id") }))),
            )
        }

        assertTrue("cast-id" in failure.message.orEmpty())
    }

    // ---- casting to a named shape ----

    @Test
    fun `casts an object to a shape when its declared fields are present`() = runTest {
        coEvery { shapeService.getByName("ReleaseBundle") } returns PipelineNamedShape(
            name = "ReleaseBundle",
            fields = listOf(ShapeField("version", "String"), ShapeField("repositories", "List")),
        )
        val obj = buildJsonObject {
            put("version", "1.2.0")
            put("repositories", buildJsonArray { add(JsonPrimitive("core")) })
        }
        assertEquals(obj, cast("shape:ReleaseBundle", PipelineValue.ofJson(obj))?.value)
    }

    @Test
    fun `an object missing a shape field fails the cast`() = runTest {
        coEvery { shapeService.getByName("ReleaseBundle") } returns PipelineNamedShape(
            name = "ReleaseBundle",
            fields = listOf(ShapeField("version", "String")),
        )
        val message = failure("shape:ReleaseBundle", PipelineValue.ofJson(buildJsonObject { put("other", 1) }))
        assertTrue("does not match shape 'ReleaseBundle'" in message && "'version'" in message, message)
    }

    @Test
    fun `a non-object cannot be cast to a shape`() = runTest {
        coEvery { shapeService.getByName("ReleaseBundle") } returns PipelineNamedShape("ReleaseBundle")
        val message = failure("shape:ReleaseBundle", PipelineValue.ofJson(JsonPrimitive("text")))
        assertTrue("expected an object" in message, message)

        val arrayMessage = failure(
            "shape:ReleaseBundle",
            PipelineValue.ofJson(buildJsonArray { add(JsonPrimitive("text")) }),
        )
        assertTrue("an array" in arrayMessage, arrayMessage)
    }

    @Test
    fun `an unknown shape fails with a clear message`() = runTest {
        coEvery { shapeService.getByName("Nope") } returns null
        assertTrue("unknown shape 'Nope'" in failure("shape:Nope", PipelineValue.ofJson(buildJsonObject { })))
    }

    // ---- casting to an email template payload ----

    /** The `welcome` template's contract: a required string `name`. */
    private fun hostWelcomeTemplate(payloadSchema: JsonObject? = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("name", buildJsonObject { put("type", "string") }) })
        put("required", buildJsonArray { add(JsonPrimitive("name")) })
    }) {
        coEvery { emailRenderer.hostedProjects() } returns listOf(
            BmlMessageHostedProject(
                project = "transactional",
                activeVersion = "1.0.0",
                templates = listOf(BmlMessageTemplateInfo(key = "welcome", payloadSchema = payloadSchema)),
            ),
        )
    }

    @Test
    fun `casts an object matching the template's payload contract`() = runTest {
        hostWelcomeTemplate()
        val obj = buildJsonObject { put("name", "Alex") }
        assertEquals(obj, cast("email:transactional/welcome", PipelineValue.ofJson(obj))?.value)
    }

    @Test
    fun `a payload violating the template contract fails with the violations`() = runTest {
        hostWelcomeTemplate()
        val message = failure("email:transactional/welcome", PipelineValue.ofJson(buildJsonObject { put("other", 1) }))
        assertTrue("payload contract" in message && "name" in message, message)
    }

    @Test
    fun `a template with no payload cannot be a cast target`() = runTest {
        hostWelcomeTemplate(payloadSchema = null)
        assertTrue("takes no payload" in failure("email:transactional/welcome", PipelineValue.ofJson(buildJsonObject { })))
    }

    @Test
    fun `an unknown message project fails with a clear message`() = runTest {
        hostWelcomeTemplate()
        assertTrue("unknown message project 'nope'" in failure("email:nope/welcome", PipelineValue.ofJson(buildJsonObject { })))
    }

    @Test
    fun `an unknown template fails with a clear message`() = runTest {
        hostWelcomeTemplate()
        val message = failure("email:transactional/nope", PipelineValue.ofJson(buildJsonObject { }))
        assertTrue("no template 'nope'" in message, message)
    }

    @Test
    fun `a malformed email reference fails rather than resolving nothing`() = runTest {
        val message = failure("email:welcome", PipelineValue.ofJson(buildJsonObject { }))
        assertTrue("not a valid email template reference" in message, message)

        val missingProject = failure("email:/welcome", PipelineValue.ofJson(buildJsonObject { }))
        assertTrue("not a valid email template reference" in missingProject, missingProject)
    }

    @Test
    fun `a non-object cannot be cast to an email payload`() = runTest {
        hostWelcomeTemplate()
        val message = failure("email:transactional/welcome", PipelineValue.ofJson(JsonPrimitive("text")))
        assertTrue("expected an object" in message, message)
    }

    // ---- configuration failures ----

    @Test
    fun `an unconfigured target fails rather than passing an unchecked value on`() = runTest {
        assertTrue("no target type is configured" in failure("", PipelineValue.ofJson(buildJsonObject { })))
    }

    @Test
    fun `requires an input`() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            CastNode(id = "c", outputType = "test.cast.User").run(context, NodeInputs(emptyMap()))
        }
        assertTrue("required input 'in'" in (e.message ?: ""), e.message ?: "")
    }

    // ---- declared output ----

    @Test
    fun `declares its target type so type-pinned slots accept the wire at save time`() {
        val node = CastNode(id = "c", outputType = "test.cast.User")
        assertEquals(SlotKind.OBJECT, node.declaredOutputKind)
        assertEquals("test.cast.User", node.declaredOutputType)
    }

    @Test
    fun `declares nothing when unconfigured`() {
        assertEquals(SlotKind.ANY, CastNode(id = "c").declaredOutputKind)
        assertEquals("", CastNode(id = "c").declaredOutputType)
    }

    // ---- serialization ----

    @Test
    fun `serializes and round-trips with defaults`() {
        val node = CastNode(id = "c", name = "Reinterpret", description = "d", outputType = "test.cast.User")
        val decoded = Json.decodeFromString(CastNode.serializer(), Json.encodeToString(CastNode.serializer(), node))
        assertEquals(node.id, decoded.id)
        assertEquals(node.name, decoded.name)
        assertEquals(node.description, decoded.description)
        assertEquals("test.cast.User", decoded.outputType)
        val minimal = Json.decodeFromString(CastNode.serializer(), """{"id":"only"}""")
        assertEquals("only", minimal.id)
        assertEquals("", minimal.outputType)
    }

    @Test
    fun `decoding JSON missing the required id throws`() {
        assertFailsWith<SerializationException> { Json.decodeFromString(CastNode.serializer(), "{}") }
    }
}
