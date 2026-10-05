package bosca.pipelines

import bosca.pipelines.builtin.JsonataNode
import bosca.pipelines.builtin.ObjectsToMapNode
import bosca.pipelines.builtin.SendEmailTemplateNode
import bosca.pipelines.builtin.SerializableToJsonNode
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineEdge
import bosca.pipelines.model.RetryPolicy
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.OutputNode
import bosca.pipelines.node.PipelineNode
import bosca.pipelines.node.PipelinesPipelineNodeSerializersProvider
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Validates that a [Pipeline] with a heterogeneous, polymorphic node graph round-trips through
 * JSON using only **explicit** subclass serializers — the exact registration the KSP node generator
 * will emit, and the proof that the design is GraalVM-native safe (no reflective polymorphism).
 */
class PipelineSerializationTest {

    private val json = Json {
        encodeDefaults = false
        serializersModule = SerializersModule {
            polymorphic(PipelineNode::class) {
                subclass(InputNode::class, InputNode.serializer())
                subclass(OutputNode::class, OutputNode.serializer())
                subclass(JsonataNode::class, JsonataNode.serializer())
                subclass(SerializableToJsonNode::class, SerializableToJsonNode.serializer())
                subclass(ObjectsToMapNode::class, ObjectsToMapNode.serializer())
            }
        }
    }

    @Test
    fun `a pipeline round-trips through polymorphic node de-serialization`() {
        val original = Pipeline(
            id = Uuid.random(),
            name = "extract-email",
            acceptedInputType = "Person",
            nodes = listOf(
                InputNode(id = "in", acceptedType = "Person"),
                JsonataNode(id = "jx", expression = "email"),
                OutputNode(id = "out"),
            ),
            edges = listOf(PipelineEdge("e1", "in", "jx"), PipelineEdge("e2", "jx", "out")),
        )

        val encoded = json.encodeToString(Pipeline.serializer(), original)
        val decoded = json.decodeFromString(Pipeline.serializer(), encoded)

        assertTrue(encoded.contains("\"type\":\"jsonata\""), "polymorphic discriminator should be emitted")
        assertEquals(3, decoded.nodes.size)
        assertTrue(decoded.nodes[0] is InputNode)
        val jx = decoded.nodes[1]
        assertTrue(jx is JsonataNode)
        assertEquals("email", jx.expression)
        assertTrue(decoded.nodes[2] is OutputNode)
        assertEquals(2, decoded.edges.size)
    }

    @Test
    fun `per-node reliability config round-trips on the base node`() {
        // retry/timeout live as var body properties on the PipelineNode base — prove
        // they (de)serialize for a concrete subclass via its explicit serializer (native-safe).
        val jx = JsonataNode(id = "jx", expression = "email").apply {
            retry = RetryPolicy(maxAttempts = 3, initialDelaySeconds = 2, multiplier = 2.0, maxDelaySeconds = 30)
            timeoutSeconds = 45
        }
        val original = Pipeline(
            id = Uuid.random(),
            name = "reliable",
            acceptedInputType = "Person",
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), jx, OutputNode(id = "out")),
            edges = listOf(PipelineEdge("e1", "in", "jx"), PipelineEdge("e2", "jx", "out")),
        )

        val decoded = json.decodeFromString(Pipeline.serializer(), json.encodeToString(Pipeline.serializer(), original))
        val decodedJx = decoded.nodes[1]
        assertTrue(decodedJx is JsonataNode)
        assertEquals(RetryPolicy(maxAttempts = 3, initialDelaySeconds = 2, multiplier = 2.0, maxDelaySeconds = 30), decodedJx.retry)
        assertEquals(45L, decodedJx.timeoutSeconds)
        // A node without config keeps the null defaults.
        assertEquals(null, decoded.nodes[0].retry)
        assertEquals(null, decoded.nodes[0].timeoutSeconds)
    }

    @Test
    fun `no node property may collide with the polymorphic discriminator`() {
        // Regression: Send Email Template's notification-type setting was named
        // `type` — the discriminator key itself — so the setting value overwrote the discriminator
        // in the flat node JSON and every pipeline containing the node failed to deserialize.
        // Encode against the REAL generated registration, not a hand-built module.
        val real = Json {
            encodeDefaults = false
            serializersModule = PipelinesPipelineNodeSerializersProvider().module
        }
        val serializer = PolymorphicSerializer(PipelineNode::class)
        val encoded = real.encodeToString(
            serializer,
            SendEmailTemplateNode(id = "send", project = "acme", template = "welcome", notificationType = "transactional"),
        )

        assertTrue("\"type\":\"sendEmailTemplate\"" in encoded, encoded)
        assertTrue("\"notificationType\":\"transactional\"" in encoded, encoded)

        val decoded = real.decodeFromString(serializer, encoded)
        assertTrue(decoded is SendEmailTemplateNode)
        assertEquals("transactional", decoded.notificationType)
    }
}
