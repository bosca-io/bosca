package bosca.pipelines

import bosca.pipelines.builtin.ConditionNode
import bosca.pipelines.builtin.DelayNode
import bosca.pipelines.builtin.ExecuteJobNode
import bosca.pipelines.builtin.ExecuteScriptNode
import bosca.pipelines.builtin.ForEach
import bosca.pipelines.builtin.JsonToSerializableNode
import bosca.pipelines.builtin.JsonataNode
import bosca.pipelines.builtin.ObjectsToMapNode
import bosca.pipelines.builtin.RunPipelineNode
import bosca.pipelines.builtin.SendEmailNode
import bosca.pipelines.builtin.SendSlackNode
import bosca.pipelines.builtin.SendWebhookNode
import bosca.pipelines.builtin.SerializableToJsonNode
import bosca.pipelines.builtin.SwitchCase
import bosca.pipelines.builtin.SwitchNode
import bosca.pipelines.builtin.WaitUntilNode
import bosca.pipelines.model.RetryPolicy
import bosca.pipelines.node.EntityReference
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.OutputNode
import bosca.pipelines.node.PipelineNode
import bosca.pipelines.node.PipelineResumeCorrelation
import bosca.serialization.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Branch coverage for the **compiler-generated** kotlinx.serialization serializers of every concrete
 * `@Serializable` pipeline node type ([bosca.pipelines.builtin]) and the node value types
 * ([bosca.pipelines.node]).
 *
 * A generated serializer emits, per optional field, `if (encodeDefaults || value != default)
 * encodeElement(...)`. With `encodeDefaults = false` (the default, kept so here):
 *  - encoding a **fully-populated** instance (every field non-default) takes the "encode the field" arm;
 *  - encoding a **defaults-only** instance (only the required, no-default constructor args set) takes
 *    the "skip the field" arm — the arm the happy-path tests otherwise miss;
 *  - decoding the resulting minimal JSON (defaults omitted) takes the "field absent → default" decode arms.
 *
 * So every type is round-tripped twice — once full, once defaults-only — through its **explicit**
 * `Type.serializer()` (never reflective). The node classes are open classes with reference equality
 * (not data classes, so no `equals`/`copy`), so their round-trips assert meaningful fields rather than
 * instance equality; the value types (data classes) assert full equality.
 *
 * The node base [PipelineNode] also carries the body-property `var`s `retry`, `timeoutSeconds` and
 * `rollbackPipeline`, which the subclass serializer emits as optional fields — the "full" node
 * instances set all three non-default so those inherited encode arms are covered too.
 */
class SerializerArmsRoundTripTest {

    private val serializers = SerializersModule {
        contextual(bosca.serialization.UUID::class, bosca.serialization.UUIDSerializer())
        contextual(java.time.OffsetDateTime::class, bosca.serialization.OffsetDateTimeSerializer())
    }

    // encodeDefaults stays false (kotlinx default) — encoding full hits the "encode field" arm and
    // encoding defaults-only hits the "skip field" arm.
    private val json = Json { serializersModule = serializers }

    // Same module with encodeDefaults on — encoding takes the `shouldEncodeElementDefault` (left) arm
    // of each generated field guard, which the default-off [json] never reaches.
    private val jsonEncodeDefaults = Json {
        encodeDefaults = true
        serializersModule = serializers
    }

    private val uuid1 = UUID.random()
    private val uuid2 = UUID.random()

    /** Set every inherited body-property `var` on [node] to a non-default value (covers their encode arms). */
    private fun <T : PipelineNode> T.withFullBodyProps(): T = apply {
        retry = RetryPolicy(maxAttempts = 5, initialDelaySeconds = 2, multiplier = 2.0, maxDelaySeconds = 60)
        timeoutSeconds = 120
        rollbackPipeline = uuid2
    }

    /** Round-trip [instance] through [serializer] and return the decoded result. */
    private fun <T> roundTrip(serializer: KSerializer<T>, instance: T): T =
        json.decodeFromString(serializer, json.encodeToString(serializer, instance))

    /**
     * Round-trip a node both fully-populated and defaults-only, asserting [field] on each decoded
     * instance. The full instance also carries the inherited body-property `var`s, which the decoded
     * full instance must echo back.
     */
    private fun <T : PipelineNode> assertNodeArms(
        serializer: KSerializer<T>,
        full: T,
        defaultsOnly: T,
        field: (T) -> Any?,
    ) {
        // encodeDefaults=on encode of the defaults-only instance takes the left arm of each field guard.
        jsonEncodeDefaults.encodeToString(serializer, defaultsOnly)
        val decodedFull = roundTrip(serializer, full)
        assertEquals(full.id, decodedFull.id, "round-trip must preserve id")
        assertEquals(field(full), field(decodedFull), "round-trip must preserve the asserted field (full)")
        // The inherited body-property arms: non-default on `full`, so they encode and decode back.
        assertEquals(full.retry, decodedFull.retry, "round-trip must preserve retry")
        assertEquals(full.timeoutSeconds, decodedFull.timeoutSeconds, "round-trip must preserve timeoutSeconds")
        assertEquals(full.rollbackPipeline, decodedFull.rollbackPipeline, "round-trip must preserve rollbackPipeline")

        val decodedDefaults = roundTrip(serializer, defaultsOnly)
        assertEquals(defaultsOnly.id, decodedDefaults.id, "round-trip must preserve id (defaults-only)")
        assertEquals(field(defaultsOnly), field(decodedDefaults), "round-trip must preserve the asserted field (defaults-only)")
        // Defaults-only nodes leave the body-property vars at their defaults (the "skip" encode arm).
        assertNull(decodedDefaults.retry, "defaults-only node decodes retry as null")
        assertNull(decodedDefaults.timeoutSeconds, "defaults-only node decodes timeoutSeconds as null")
        assertNull(decodedDefaults.rollbackPipeline, "defaults-only node decodes rollbackPipeline as null")
    }

    // =====================================================================================
    // builtin nodes
    // =====================================================================================

    @Test
    fun conditionNode() {
        assertNodeArms(
            ConditionNode.serializer(),
            full = ConditionNode(
                id = "n", name = "If", description = "d", expression = "a > 1",
                position = NodePosition(1.0, 2.0),
            ).withFullBodyProps(),
            defaultsOnly = ConditionNode(id = "n", expression = "a > 1"),
        ) { it.expression }
    }

    @Test
    fun delayNode() {
        assertNodeArms(
            DelayNode.serializer(),
            full = DelayNode(
                id = "n", name = "Wait", description = "d", delaySeconds = 30,
                position = NodePosition(1.0, 2.0),
            ).withFullBodyProps(),
            defaultsOnly = DelayNode(id = "n"),
        ) { it.delaySeconds }
    }

    @Test
    fun executeJobNode() {
        assertNodeArms(
            ExecuteJobNode.serializer(),
            full = ExecuteJobNode(
                id = "n", name = "Job", description = "d", jobName = "doThing",
                awaitCompletion = true, awaitTimeoutSeconds = 30, position = NodePosition(1.0, 2.0),
            ).withFullBodyProps(),
            defaultsOnly = ExecuteJobNode(id = "n", jobName = "doThing"),
        ) { it.jobName to it.awaitCompletion }
    }

    @Test
    fun executeScriptNode() {
        assertNodeArms(
            ExecuteScriptNode.serializer(),
            full = ExecuteScriptNode(
                id = "n", name = "Script", description = "d", scriptId = uuid1,
                dryRunEnabled = true, position = NodePosition(1.0, 2.0),
            ).withFullBodyProps(),
            defaultsOnly = ExecuteScriptNode(id = "n", scriptId = uuid1),
        ) { it.scriptId to it.dryRunEnabled }
    }

    @Test
    fun forEachNode() {
        assertNodeArms(
            ForEach.serializer(),
            full = ForEach(
                id = "n", name = "Loop", description = "d", pipelineId = uuid1, itemsField = "items",
                maxConcurrency = 4, continueOnError = true, position = NodePosition(1.0, 2.0),
            ).withFullBodyProps(),
            defaultsOnly = ForEach(id = "n"),
        ) { it.pipelineId }
    }

    @Test
    fun jsonataNode() {
        assertNodeArms(
            JsonataNode.serializer(),
            full = JsonataNode(
                id = "n", name = "Shape", description = "d", expression = "{ \"x\": a }",
                position = NodePosition(1.0, 2.0),
            ).withFullBodyProps(),
            defaultsOnly = JsonataNode(id = "n", expression = "a"),
        ) { it.expression }
    }

    @Test
    fun jsonToSerializableNode() {
        assertNodeArms(
            JsonToSerializableNode.serializer(),
            full = JsonToSerializableNode(
                id = "n", name = "ToTyped", description = "d", position = NodePosition(1.0, 2.0),
            ).withFullBodyProps(),
            defaultsOnly = JsonToSerializableNode(id = "n"),
        ) { it.name }
    }

    @Test
    fun objectsToMapNode() {
        assertNodeArms(
            ObjectsToMapNode.serializer(),
            full = ObjectsToMapNode(
                id = "n", name = "Merge", description = "d", position = NodePosition(1.0, 2.0),
            ).withFullBodyProps(),
            defaultsOnly = ObjectsToMapNode(id = "n"),
        ) { it.name }
    }

    @Test
    fun runPipelineNode() {
        assertNodeArms(
            RunPipelineNode.serializer(),
            full = RunPipelineNode(
                id = "n", name = "Sub", description = "d", pipelineId = uuid1, position = NodePosition(1.0, 2.0),
            ).withFullBodyProps(),
            defaultsOnly = RunPipelineNode(id = "n"),
        ) { it.pipelineId }
    }

    @Test
    fun sendEmailNode() {
        assertNodeArms(
            SendEmailNode.serializer(),
            full = SendEmailNode(
                id = "n", name = "Email", description = "d", recipients = listOf(uuid1, uuid2),
                subject = "Hi", body = "<p>hi</p>", html = false, position = NodePosition(1.0, 2.0),
            ).withFullBodyProps(),
            defaultsOnly = SendEmailNode(id = "n"),
        ) { it.recipients to it.subject }
    }

    @Test
    fun sendSlackNode() {
        assertNodeArms(
            SendSlackNode.serializer(),
            full = SendSlackNode(
                id = "n", name = "Slack", description = "d",
                webhookSecret = "slack-secret", webhookUrl = "https://hooks/x",
                text = "msg", position = NodePosition(1.0, 2.0),
            ).withFullBodyProps(),
            defaultsOnly = SendSlackNode(id = "n", webhookSecret = "slack-secret"),
        ) { listOf(it.webhookSecret, it.webhookUrl, it.text) }
    }

    @Test
    fun sendWebhookNode() {
        assertNodeArms(
            SendWebhookNode.serializer(),
            full = SendWebhookNode(
                id = "n", name = "Hook", description = "d",
                urlSecret = "url-secret", signingSecret = "sign-secret",
                url = "https://x/y", secret = "s3cret", position = NodePosition(1.0, 2.0),
            ).withFullBodyProps(),
            defaultsOnly = SendWebhookNode(id = "n", urlSecret = "url-secret"),
        ) { listOf(it.urlSecret, it.signingSecret, it.url, it.secret) }
    }

    @Test
    fun serializableToJsonNode() {
        assertNodeArms(
            SerializableToJsonNode.serializer(),
            full = SerializableToJsonNode(
                id = "n", name = "ToJson", description = "d", position = NodePosition(1.0, 2.0),
            ).withFullBodyProps(),
            defaultsOnly = SerializableToJsonNode(id = "n"),
        ) { it.name }
    }

    @Test
    fun switchNode() {
        assertNodeArms(
            SwitchNode.serializer(),
            full = SwitchNode(
                id = "n", name = "Route", description = "d",
                cases = listOf(SwitchCase(label = "a", expression = "x = 1")),
                defaultLabel = "other", position = NodePosition(1.0, 2.0),
            ).withFullBodyProps(),
            defaultsOnly = SwitchNode(id = "n"),
        ) { it.cases.size to it.defaultLabel }
    }

    @Test
    fun waitUntilNode() {
        assertNodeArms(
            WaitUntilNode.serializer(),
            full = WaitUntilNode(
                id = "n", name = "Until", description = "d", untilField = "order.shipBy",
                until = "2026-07-01T09:00:00Z", position = NodePosition(1.0, 2.0),
            ).withFullBodyProps(),
            defaultsOnly = WaitUntilNode(id = "n"),
        ) { it.untilField to it.until }
    }

    // =====================================================================================
    // node value types (InputNode / OutputNode are nodes; the rest are data/value classes)
    // =====================================================================================

    @Test
    fun inputNode() {
        assertNodeArms(
            InputNode.serializer(),
            full = InputNode(
                id = "in", name = "Start", description = "d", acceptedType = InputNode.JSON_TYPE,
                schema = buildJsonObject { put("type", "object") }, position = NodePosition(1.0, 2.0),
            ).withFullBodyProps(),
            defaultsOnly = InputNode(id = "in", acceptedType = "io.bosca.SomeEvent"),
        ) { it.acceptedType to it.schema }
    }

    @Test
    fun outputNode() {
        assertNodeArms(
            OutputNode.serializer(),
            full = OutputNode(
                id = "out", name = "End", description = "d", outputType = "JSON",
                schema = buildJsonObject { put("type", "string") }, position = NodePosition(3.0, 4.0),
            ).withFullBodyProps(),
            defaultsOnly = OutputNode(id = "out"),
        ) { it.outputType to it.schema }
    }

    @Test
    fun nodePosition() {
        // Data class: assert full value equality on both arms.
        assertEquals(NodePosition(7.0, 9.0), roundTrip(NodePosition.serializer(), NodePosition(7.0, 9.0)))
        assertEquals(NodePosition(), roundTrip(NodePosition.serializer(), NodePosition()))
        // encodeDefaults=on covers the left arm of NodePosition's field guards.
        jsonEncodeDefaults.encodeToString(NodePosition.serializer(), NodePosition())
        // Generated equals arms: identity true, wrong-type false, and a per-field not-equal arm for
        // each of x and y (each Double.compare branch) — the round-trip above only ran the all-equal arm.
        val p = NodePosition(7.0, 9.0)
        assertEquals(p, p)
        assertFalse(p.equals(Any()))
        assertNotEquals(p, NodePosition(8.0, 9.0)) // x differs
        assertNotEquals(p, NodePosition(7.0, 10.0)) // y differs (x equal -> falls to the y compare)
        assertEquals(p.hashCode(), NodePosition(7.0, 9.0).hashCode())
        // The generated serializer's NON-sequential decode loop (the `decodeSequentially()==false` arm
        // the string decoder above skips): the JSON tree decoder drives the element-by-element loop.
        assertEquals(p, json.decodeFromJsonElement(NodePosition.serializer(), json.encodeToJsonElement(NodePosition.serializer(), p)))
    }

    /**
     * The synthetic deserialization constructor (`<init>(seen, …, marker)`) generated for the
     * `@Serializable` [InputNode]/[OutputNode] (InputOutputNodes.kt:22 / :59) throws via
     * `throwMissingFieldException` when a REQUIRED field is absent from the wire. The node round-trip
     * tests only ever decode JSON that carries every required field, so that throw arm stays uncovered.
     * Decoding JSON missing the required `id` drives it.
     */
    @Test
    fun inputOutputNodeMissingRequiredFieldThrows() {
        // InputNode requires `id` (acceptedType also required) — omit `id`.
        assertFailsWith<SerializationException> {
            json.decodeFromString(InputNode.serializer(), """{"acceptedType":"T"}""")
        }
        // OutputNode requires only `id` — omit it.
        assertFailsWith<SerializationException> {
            json.decodeFromString(OutputNode.serializer(), """{"outputType":"JSON"}""")
        }
    }

    /**
     * [EntityReference] (EntityReference.kt:35) is a plain class (not `@Serializable`) with a single
     * trailing optional `version`. Constructing it while OMITTING `version` routes through the
     * synthetic `<init>$default`, which applies the `null` default; supplying it uses the primary
     * constructor's stored value. (Both reachable construction shapes are asserted here.)
     */
    @Test
    fun entityReferenceConstruction() {
        // Omit version -> `<init>$default` applies the null default.
        val noVersion = EntityReference(id = uuid1)
        assertEquals(uuid1, noVersion.id)
        assertNull(noVersion.version)
        // Supply version -> primary constructor stores it.
        val withVersion = EntityReference(id = uuid1, version = 3)
        assertEquals(3, withVersion.version)
    }

    @Test
    fun switchCase() {
        // SwitchCase has no optional fields — both required; round-trip preserves equality.
        val case = SwitchCase(label = "a", expression = "x = 1")
        assertEquals(case, roundTrip(SwitchCase.serializer(), case))
    }

    @Test
    fun pipelineResumeCorrelation() {
        // Both fields are required (no defaults) — round-trip preserves equality.
        val correlation = PipelineResumeCorrelation(runId = uuid1, nodeId = "n")
        val decoded = roundTrip(PipelineResumeCorrelation.serializer(), correlation)
        assertEquals(correlation, decoded)
        assertNotNull(decoded.runId)
        // Generated equals arms: identity (this === other) true arm, wrong-type (other !is) false arm,
        // and one single-field-mutated copy per field so each per-field not-equal arm runs.
        assertEquals(correlation, correlation)
        assertFalse(correlation.equals(Any()))
        assertNotEquals(correlation, correlation.copy(runId = uuid2))
        assertNotEquals(correlation, correlation.copy(nodeId = "m"))
        assertEquals(correlation.hashCode(), decoded.hashCode())
        // Deserialization ctor throw arm: a payload missing the required `nodeId`.
        val runIdJson = json.encodeToString(bosca.serialization.UUIDSerializer(), uuid1)
        assertFailsWith<SerializationException> {
            json.decodeFromString(
                PipelineResumeCorrelation.serializer(),
                """{"runId":$runIdJson}""",
            )
        }
    }
}
