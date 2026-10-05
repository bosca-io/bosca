package bosca.pipelines.node

import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.model.PipelineEdge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlotConnectionValidatorTest {

    private fun descriptor(
        key: String,
        outputKind: SlotKind = SlotKind.ANY,
        inputs: List<NodeInputSlot> = emptyList(),
        outputType: String? = null,
        outputs: List<NodeOutputSlot> = emptyList(),
    ) = NodeDescriptor(
        key = key, label = key, category = NodeCategory.TRANSFORM, inputs = inputs,
        // A single-output node: synthesize its lone "out" slot from outputKind/outputType, unless the
        // caller passed explicit named outputs. An ANY/untyped output stays implicit (no outputs).
        outputs = outputs.ifEmpty {
            if (outputKind == SlotKind.ANY && outputType == null) emptyList()
            else listOf(NodeOutputSlot(name = "out", kind = outputKind, type = outputType))
        },
    )

    private fun slot(name: String, kind: SlotKind, type: String? = null, typeLabel: String = kind.name) =
        NodeInputSlot(name = name, typeLabel = typeLabel, kind = kind, type = type)

    private fun outputPort(name: String, kind: SlotKind = SlotKind.ANY, type: String? = null, error: Boolean = false) =
        NodeOutputSlot(name = name, kind = kind, error = error, type = type)

    @Test
    fun `an incompatible concrete edge is rejected`() {
        val descriptors = mapOf(
            "intSource" to descriptor("intSource", outputKind = SlotKind.INTEGER),
            "uuidSink" to descriptor("uuidSink", inputs = listOf(slot("id", SlotKind.UUID))),
        )
        val keyById = mapOf("a" to "intSource", "b" to "uuidSink")
        val edges = listOf(PipelineEdge(id = "e", source = "a", target = "b", targetPort = "id"))

        val violations = SlotConnectionValidator.validate(keyById, edges, descriptors)
        assertEquals(1, violations.size)
        assertTrue(violations.single().contains("expects uuid but is fed integer"), violations.single())
    }

    @Test
    fun `an untyped array source (e_g_ Flatten) is trusted into a typed array slot`() {
        val descriptors = mapOf(
            // A generic array transform (Flatten) declares an ARRAY output with no element type.
            "flatten" to descriptor("flatten", outputKind = SlotKind.ARRAY, outputType = null),
            "sink" to descriptor(
                "sink",
                inputs = listOf(slot("artifacts", SlotKind.ARRAY, type = "bosca.git.model.ArtifactDefinition")),
            ),
        )
        val keyById = mapOf("f" to "flatten", "s" to "sink")
        val edges = listOf(PipelineEdge(id = "e", source = "f", target = "s", targetPort = "artifacts"))
        assertEquals(emptyList(), SlotConnectionValidator.validate(keyById, edges, descriptors))
    }

    @Test
    fun `a typed array source of the wrong element type is still rejected`() {
        val descriptors = mapOf(
            "versions" to descriptor("versions", outputKind = SlotKind.ARRAY, outputType = "bosca.workops.model.Version"),
            "sink" to descriptor(
                "sink",
                inputs = listOf(slot("artifacts", SlotKind.ARRAY, type = "bosca.git.model.ArtifactDefinition")),
            ),
        )
        val keyById = mapOf("v" to "versions", "s" to "sink")
        val edges = listOf(PipelineEdge(id = "e", source = "v", target = "s", targetPort = "artifacts"))
        val violations = SlotConnectionValidator.validate(keyById, edges, descriptors)
        assertEquals(1, violations.size)
        assertTrue(violations.single().contains("Version"), violations.single())
    }

    @Test
    fun `an integer widens into a number slot`() {
        val descriptors = mapOf(
            "intSource" to descriptor("intSource", outputKind = SlotKind.INTEGER),
            "numberSink" to descriptor("numberSink", inputs = listOf(slot("n", SlotKind.NUMBER))),
        )
        val keyById = mapOf("i" to "intSource", "n" to "numberSink")
        val edges = listOf(PipelineEdge(id = "e", source = "i", target = "n", targetPort = "n"))
        assertEquals(emptyList(), SlotConnectionValidator.validate(keyById, edges, descriptors))
    }

    @Test
    fun `a uuid does NOT widen into a string slot - it needs an explicit To String`() {
        // A UUID is an identifier, not arbitrary text: feeding a UUID source (e.g. a Get Id node) into a
        // string slot is refused at connect time, so the author converts it deliberately (a To String node).
        val descriptors = mapOf(
            "uuidSource" to descriptor("uuidSource", outputKind = SlotKind.UUID),
            "stringSink" to descriptor("stringSink", inputs = listOf(slot("s", SlotKind.STRING))),
        )
        val keyById = mapOf("u" to "uuidSource", "s" to "stringSink")
        val edges = listOf(PipelineEdge(id = "e", source = "u", target = "s", targetPort = "s"))
        val violations = SlotConnectionValidator.validate(keyById, edges, descriptors)
        assertEquals(1, violations.size)
        assertTrue(violations.single().contains("expects string but is fed uuid"), violations.single())
    }

    @Test
    fun `a dynamic ANY source into a typed slot is rejected as unknown`() {
        // "If you can't prove it's the right kind, it isn't": a JSONata (ANY) output into a UUID slot
        // is refused, with a hint to insert a Get Id node.
        val descriptors = mapOf(
            "jsonata" to descriptor("jsonata", outputKind = SlotKind.ANY),
            "uuidSink" to descriptor("uuidSink", inputs = listOf(slot("id", SlotKind.UUID))),
        )
        val keyById = mapOf("j" to "jsonata", "b" to "uuidSink")
        val edges = listOf(PipelineEdge(id = "e", source = "j", target = "b", targetPort = "id"))
        val violations = SlotConnectionValidator.validate(keyById, edges, descriptors)
        assertEquals(1, violations.size)
        assertTrue(violations.single().contains("produces an unknown value"), violations.single())
        assertTrue(violations.single().contains("Get Id"), violations.single())
    }

    @Test
    fun `an unknown source into a non-uuid typed slot is rejected without the get-id hint`() {
        // The no-hint branch of the violation message: the slot is not a UUID, so no Get Id pointer.
        val descriptors = mapOf(
            "jsonata" to descriptor("jsonata", outputKind = SlotKind.ANY),
            "stringSink" to descriptor("stringSink", inputs = listOf(slot("s", SlotKind.STRING))),
        )
        val keyById = mapOf("j" to "jsonata", "b" to "stringSink")
        val edges = listOf(PipelineEdge(id = "e", source = "j", target = "b", targetPort = "s"))
        val violations = SlotConnectionValidator.validate(keyById, edges, descriptors)
        assertEquals(1, violations.size)
        assertTrue(violations.single().contains("produces an unknown value"), violations.single())
        assertTrue(!violations.single().contains("Get Id"), violations.single())
    }

    @Test
    fun `an unknown source into an ANY slot is allowed`() {
        // The Input event -> Get Document (entity-operator) case: an ANY slot accepts the unknown
        // value because entityRef reads it at run time. Checked before the source kind matters.
        val descriptors = mapOf(
            "anySink" to descriptor("anySink", inputs = listOf(slot("x", SlotKind.ANY))),
        )
        val keyById = mapOf("b" to "anySink") // source "a" absent (an unknown/Input-style source)
        val edges = listOf(PipelineEdge(id = "e", source = "a", target = "b", targetPort = "x"))
        assertEquals(emptyList(), SlotConnectionValidator.validate(keyById, edges, descriptors))
    }

    @Test
    fun `a single-slot sink matches regardless of the edge port`() {
        val descriptors = mapOf(
            "intSource" to descriptor("intSource", outputKind = SlotKind.INTEGER),
            "uuidSink" to descriptor("uuidSink", inputs = listOf(slot("id", SlotKind.UUID))),
        )
        val keyById = mapOf("a" to "intSource", "b" to "uuidSink")
        // no targetPort set — a single-slot node still matches its sole slot
        val edges = listOf(PipelineEdge(id = "e", source = "a", target = "b"))
        assertEquals(1, SlotConnectionValidator.validate(keyById, edges, descriptors).size)
    }

    // ---- added coverage ----

    @Test
    fun `an edge whose target is an unknown node id is skipped`() {
        // L30 `?: continue` arm: edge.target not in nodeKeyById -> target resolves null.
        val descriptors = mapOf(
            "intSource" to descriptor("intSource", outputKind = SlotKind.INTEGER),
            "uuidSink" to descriptor("uuidSink", inputs = listOf(slot("id", SlotKind.UUID))),
        )
        val keyById = mapOf("a" to "intSource") // "b" deliberately absent
        val edges = listOf(PipelineEdge(id = "e", source = "a", target = "b", targetPort = "id"))
        assertEquals(emptyList(), SlotConnectionValidator.validate(keyById, edges, descriptors))
    }

    @Test
    fun `an edge whose target key has no descriptor is skipped`() {
        // L30 `?: continue` arm: the id maps to a key that isn't in descriptors.
        val descriptors = mapOf(
            "intSource" to descriptor("intSource", outputKind = SlotKind.INTEGER),
        )
        val keyById = mapOf("a" to "intSource", "b" to "missingSink")
        val edges = listOf(PipelineEdge(id = "e", source = "a", target = "b", targetPort = "id"))
        assertEquals(emptyList(), SlotConnectionValidator.validate(keyById, edges, descriptors))
    }

    @Test
    fun `an edge into a target with no input slots is skipped`() {
        // L31 `continue` arm: target.inputs.isEmpty().
        val descriptors = mapOf(
            "intSource" to descriptor("intSource", outputKind = SlotKind.INTEGER),
            "sinkNoInputs" to descriptor("sinkNoInputs", inputs = emptyList()),
        )
        val keyById = mapOf("a" to "intSource", "b" to "sinkNoInputs")
        val edges = listOf(PipelineEdge(id = "e", source = "a", target = "b", targetPort = "id"))
        assertEquals(emptyList(), SlotConnectionValidator.validate(keyById, edges, descriptors))
    }

    @Test
    fun `an unknown source node is treated as ANY and rejected by a typed slot`() {
        // Source id resolves to no descriptor (e.g. the Input node) -> sourceKind defaults to ANY,
        // which a typed slot refuses.
        val descriptors = mapOf(
            "uuidSink" to descriptor("uuidSink", inputs = listOf(slot("id", SlotKind.UUID))),
        )
        val keyById = mapOf("b" to "uuidSink") // source "a" absent
        val edges = listOf(PipelineEdge(id = "e", source = "a", target = "b", targetPort = "id"))
        val violations = SlotConnectionValidator.validate(keyById, edges, descriptors)
        assertEquals(1, violations.size)
        assertTrue(violations.single().contains("produces an unknown value"), violations.single())
    }

    @Test
    fun `a multi-slot sink with no matching target port is skipped`() {
        // L34 `?: continue` arm: matchSlot returns null because no slot name matches the port.
        val descriptors = mapOf(
            "intSource" to descriptor("intSource", outputKind = SlotKind.INTEGER),
            "multiSink" to descriptor(
                "multiSink",
                inputs = listOf(slot("id", SlotKind.UUID), slot("count", SlotKind.INTEGER)),
            ),
        )
        val keyById = mapOf("a" to "intSource", "b" to "multiSink")
        val edges = listOf(PipelineEdge(id = "e", source = "a", target = "b", targetPort = "doesNotExist"))
        assertEquals(emptyList(), SlotConnectionValidator.validate(keyById, edges, descriptors))
    }

    @Test
    fun `a slot with ANY kind is left for run-time enforcement`() {
        // L35 `continue` arm: slot.kind == ANY (source kind concrete, slot kind ANY).
        val descriptors = mapOf(
            "intSource" to descriptor("intSource", outputKind = SlotKind.INTEGER),
            "anySink" to descriptor("anySink", inputs = listOf(slot("x", SlotKind.ANY))),
        )
        val keyById = mapOf("a" to "intSource", "b" to "anySink")
        val edges = listOf(PipelineEdge(id = "e", source = "a", target = "b", targetPort = "x"))
        assertEquals(emptyList(), SlotConnectionValidator.validate(keyById, edges, descriptors))
    }

    @Test
    fun `a source key with no descriptor is treated as ANY and rejected by a typed slot`() {
        // edge.source resolves to a key via nodeKeyById, but that key has no descriptor ->
        // `descriptors[it]` is null -> sourceKind defaults to ANY -> a typed slot refuses it.
        val descriptors = mapOf(
            "uuidSink" to descriptor("uuidSink", inputs = listOf(slot("id", SlotKind.UUID))),
        )
        // "a" maps to a key that is absent from descriptors, while the target IS known.
        val keyById = mapOf("a" to "missingSource", "b" to "uuidSink")
        val edges = listOf(PipelineEdge(id = "e", source = "a", target = "b", targetPort = "id"))
        val violations = SlotConnectionValidator.validate(keyById, edges, descriptors)
        assertEquals(1, violations.size)
        assertTrue(violations.single().contains("produces an unknown value"), violations.single())
    }

    @Test
    fun `a string slot fed by a non-uuid concrete source is rejected`() {
        // A typed non-string source (INTEGER) feeding a STRING slot has no widening to fall back on, so
        // isAssignable returns false and a violation is reported.
        val descriptors = mapOf(
            "intSource" to descriptor("intSource", outputKind = SlotKind.INTEGER),
            "stringSink" to descriptor("stringSink", inputs = listOf(slot("s", SlotKind.STRING))),
        )
        val keyById = mapOf("a" to "intSource", "b" to "stringSink")
        val edges = listOf(PipelineEdge(id = "e", source = "a", target = "b", targetPort = "s"))
        val violations = SlotConnectionValidator.validate(keyById, edges, descriptors)
        assertEquals(1, violations.size)
        assertTrue(violations.single().contains("expects string but is fed integer"), violations.single())
    }

    @Test
    fun `a number slot fed by a non-integer concrete source is rejected`() {
        // L53 `source == SlotKind.INTEGER` false arm while `target == NUMBER` is true: a STRING feeding a
        // NUMBER slot is not the integer widening, so the else applies and reports a violation.
        val descriptors = mapOf(
            "stringSource" to descriptor("stringSource", outputKind = SlotKind.STRING),
            "numberSink" to descriptor("numberSink", inputs = listOf(slot("n", SlotKind.NUMBER))),
        )
        val keyById = mapOf("a" to "stringSource", "b" to "numberSink")
        val edges = listOf(PipelineEdge(id = "e", source = "a", target = "b", targetPort = "n"))
        val violations = SlotConnectionValidator.validate(keyById, edges, descriptors)
        assertEquals(1, violations.size)
        assertTrue(violations.single().contains("expects number but is fed string"), violations.single())
    }

    // ---- type-pinned object slots ----

    @Test
    fun `an object slot accepts a source producing the matching type`() {
        val descriptors = mapOf(
            "metaSource" to descriptor("metaSource", outputKind = SlotKind.OBJECT, outputType = "x.Metadata"),
            "docSink" to descriptor("docSink", inputs = listOf(slot("in", SlotKind.OBJECT, type = "x.Metadata", typeLabel = "Metadata"))),
        )
        val keyById = mapOf("a" to "metaSource", "b" to "docSink")
        val edges = listOf(PipelineEdge(id = "e", source = "a", target = "b", targetPort = "in"))
        assertEquals(emptyList(), SlotConnectionValidator.validate(keyById, edges, descriptors))
    }

    @Test
    fun `an object slot rejects a source producing a different type`() {
        // The kind matches (OBJECT->OBJECT) but the specific type does not: "object" is never enough
        // where a Metadata is required.
        val descriptors = mapOf(
            "collSource" to descriptor("collSource", outputKind = SlotKind.OBJECT, outputType = "x.Collection"),
            "docSink" to descriptor("docSink", inputs = listOf(slot("in", SlotKind.OBJECT, type = "x.Metadata", typeLabel = "Metadata"))),
        )
        val keyById = mapOf("a" to "collSource", "b" to "docSink")
        val edges = listOf(PipelineEdge(id = "e", source = "a", target = "b", targetPort = "in"))
        val v = SlotConnectionValidator.validate(keyById, edges, descriptors)
        assertEquals(1, v.size)
        assertTrue(v.single().contains("expects a Metadata"), v.single())
        assertTrue(v.single().contains("is fed Collection"), v.single())
    }

    @Test
    fun `an object slot rejects a source with no declared type`() {
        val descriptors = mapOf(
            "anyObj" to descriptor("anyObj", outputKind = SlotKind.OBJECT), // outputType null
            "docSink" to descriptor("docSink", inputs = listOf(slot("in", SlotKind.OBJECT, type = "x.Metadata", typeLabel = "Metadata"))),
        )
        val keyById = mapOf("a" to "anyObj", "b" to "docSink")
        val edges = listOf(PipelineEdge(id = "e", source = "a", target = "b", targetPort = "in"))
        val v = SlotConnectionValidator.validate(keyById, edges, descriptors)
        assertEquals(1, v.size)
        assertTrue(v.single().contains("untyped object"), v.single())
    }

    @Test
    fun `an object slot with no required type accepts any object`() {
        // The Index node's case: a polymorphic OBJECT slot (no `type`) takes any entity object.
        val descriptors = mapOf(
            "metaSource" to descriptor("metaSource", outputKind = SlotKind.OBJECT, outputType = "x.Metadata"),
            "indexSink" to descriptor("indexSink", inputs = listOf(slot("in", SlotKind.OBJECT))), // slot type null
        )
        val keyById = mapOf("a" to "metaSource", "b" to "indexSink")
        val edges = listOf(PipelineEdge(id = "e", source = "a", target = "b", targetPort = "in"))
        assertEquals(emptyList(), SlotConnectionValidator.validate(keyById, edges, descriptors))
    }

    @Test
    fun `identical concrete kinds on both ends are assignable`() {
        // L51 `source == target -> true` arm: same kind, no violation.
        val descriptors = mapOf(
            "intSource" to descriptor("intSource", outputKind = SlotKind.INTEGER),
            "intSink" to descriptor("intSink", inputs = listOf(slot("n", SlotKind.INTEGER))),
        )
        val keyById = mapOf("a" to "intSource", "b" to "intSink")
        val edges = listOf(PipelineEdge(id = "e", source = "a", target = "b", targetPort = "n"))
        assertEquals(emptyList(), SlotConnectionValidator.validate(keyById, edges, descriptors))
    }

    // ---- typed named output ports (per-port resolution by sourcePort) ----

    @Test
    fun `a named output port's type is matched against the downstream slot`() {
        // A HubSpot-write-style node: the `out` port carries a specific object type, matched against a
        // downstream slot pinned to that same type via the edge's sourcePort.
        val descriptors = mapOf(
            "writer" to descriptor("writer", outputs = listOf(outputPort("out", SlotKind.OBJECT, type = "x.Updated"))),
            "sink" to descriptor("sink", inputs = listOf(slot("in", SlotKind.OBJECT, type = "x.Updated", typeLabel = "Updated"))),
        )
        val keyById = mapOf("a" to "writer", "b" to "sink")
        val edges = listOf(PipelineEdge(id = "e", source = "a", target = "b", sourcePort = "out", targetPort = "in"))
        assertEquals(emptyList(), SlotConnectionValidator.validate(keyById, edges, descriptors))
    }

    @Test
    fun `a named output port with a different type is rejected`() {
        val descriptors = mapOf(
            "writer" to descriptor("writer", outputs = listOf(outputPort("out", SlotKind.OBJECT, type = "x.Other"))),
            "sink" to descriptor("sink", inputs = listOf(slot("in", SlotKind.OBJECT, type = "x.Updated", typeLabel = "Updated"))),
        )
        val keyById = mapOf("a" to "writer", "b" to "sink")
        val edges = listOf(PipelineEdge(id = "e", source = "a", target = "b", sourcePort = "out", targetPort = "in"))
        val v = SlotConnectionValidator.validate(keyById, edges, descriptors)
        assertEquals(1, v.size)
        assertTrue(v.single().contains("expects a Updated"), v.single())
        assertTrue(v.single().contains("is fed Other"), v.single())
    }

    @Test
    fun `a named output port's kind is honored over the node-level ANY`() {
        // The node emits on named ports, so its implicit outputKind is ANY; but the `out` port is
        // OBJECT, so an OBJECT slot accepts the wire — the kind comes from the port, not the node.
        val descriptors = mapOf(
            "writer" to descriptor("writer", outputKind = SlotKind.ANY, outputs = listOf(outputPort("out", SlotKind.OBJECT))),
            "sink" to descriptor("sink", inputs = listOf(slot("in", SlotKind.OBJECT))),
        )
        val keyById = mapOf("a" to "writer", "b" to "sink")
        val edges = listOf(PipelineEdge(id = "e", source = "a", target = "b", sourcePort = "out", targetPort = "in"))
        assertEquals(emptyList(), SlotConnectionValidator.validate(keyById, edges, descriptors))
    }

    @Test
    fun `an untyped error port into a typed object slot is refused as unknown`() {
        // An `error` port (kind ANY, no type) carries an error payload, not the typed value — feeding it
        // into a typed object slot resolves to ANY and is refused.
        val descriptors = mapOf(
            "writer" to descriptor("writer", outputs = listOf(outputPort("error", error = true))),
            "sink" to descriptor("sink", inputs = listOf(slot("in", SlotKind.OBJECT, type = "x.Updated", typeLabel = "Updated"))),
        )
        val keyById = mapOf("a" to "writer", "b" to "sink")
        val edges = listOf(PipelineEdge(id = "e", source = "a", target = "b", sourcePort = "error", targetPort = "in"))
        val v = SlotConnectionValidator.validate(keyById, edges, descriptors)
        assertEquals(1, v.size)
        assertTrue(v.single().contains("produces an unknown value"), v.single())
    }

    private fun routeDescriptor(key: String) =
        NodeDescriptor(key = key, label = key, category = NodeCategory.ROUTE, inputs = listOf(slot("in", SlotKind.ANY)))

    @Test
    fun `a routing node passes its input's typed object through to a typed slot`() {
        // A Condition routes its input unchanged, so an OBJECT(Foo) wired into it can feed an OBJECT(Foo) slot.
        val descriptors = mapOf(
            "objSource" to descriptor("objSource", outputKind = SlotKind.OBJECT, outputType = "bosca.Foo"),
            "condition" to routeDescriptor("condition"),
            "objSink" to descriptor("objSink", inputs = listOf(slot("properties", SlotKind.OBJECT, type = "bosca.Foo", typeLabel = "Foo"))),
        )
        val keyById = mapOf("src" to "objSource", "cond" to "condition", "sink" to "objSink")
        val edges = listOf(
            PipelineEdge(id = "e1", source = "src", target = "cond", targetPort = "in"),
            PipelineEdge(id = "e2", source = "cond", target = "sink", sourcePort = "true", targetPort = "properties"),
        )
        assertEquals(emptyList(), SlotConnectionValidator.validate(keyById, edges, descriptors))
    }

    @Test
    fun `a routing node propagates through a chain of routing nodes`() {
        val descriptors = mapOf(
            "uuidSource" to descriptor("uuidSource", outputKind = SlotKind.UUID),
            "if1" to routeDescriptor("if1"),
            "if2" to routeDescriptor("if2"),
            "uuidSink" to descriptor("uuidSink", inputs = listOf(slot("id", SlotKind.UUID))),
        )
        val keyById = mapOf("s" to "uuidSource", "a" to "if1", "b" to "if2", "k" to "uuidSink")
        val edges = listOf(
            PipelineEdge(id = "e1", source = "s", target = "a", targetPort = "in"),
            PipelineEdge(id = "e2", source = "a", target = "b", sourcePort = "true", targetPort = "in"),
            PipelineEdge(id = "e3", source = "b", target = "k", sourcePort = "true", targetPort = "id"),
        )
        assertEquals(emptyList(), SlotConnectionValidator.validate(keyById, edges, descriptors))
    }

    @Test
    fun `a routing node whose flow can't be pinned is trusted, not refused`() {
        // Nothing typed feeds the condition (e.g. straight off the untyped JSON Input), so its output is
        // unknown — but a routing node is a transparent passthrough, so the wire is trusted, not blocked.
        val descriptors = mapOf(
            "condition" to routeDescriptor("condition"),
            "objSink" to descriptor("objSink", inputs = listOf(slot("properties", SlotKind.OBJECT))),
        )
        val keyById = mapOf("cond" to "condition", "sink" to "objSink")
        val edges = listOf(PipelineEdge(id = "e", source = "cond", target = "sink", sourcePort = "true", targetPort = "properties"))
        assertEquals(emptyList(), SlotConnectionValidator.validate(keyById, edges, descriptors))
    }

    @Test
    fun `a routing node with an unpinned flow is NOT trusted into a scalar slot`() {
        // An unpinned passthrough into a string/uuid/number slot is almost always a mistake (scalars come
        // from specific producers), so it's caught here rather than failing at run time.
        val descriptors = mapOf(
            "condition" to routeDescriptor("condition"),
            "strSink" to descriptor("strSink", inputs = listOf(slot("id", SlotKind.STRING))),
        )
        val keyById = mapOf("cond" to "condition", "sink" to "strSink")
        val edges = listOf(PipelineEdge(id = "e", source = "cond", target = "sink", sourcePort = "true", targetPort = "id"))
        val v = SlotConnectionValidator.validate(keyById, edges, descriptors)
        assertEquals(1, v.size)
        assertTrue(v.single().contains("produces an unknown value"), v.single())
    }

    @Test
    fun `a routing node fed an incompatible type still fails through the passthrough`() {
        // A String wired into the Condition still can't feed an OBJECT slot — propagation preserves safety.
        val descriptors = mapOf(
            "strSource" to descriptor("strSource", outputKind = SlotKind.STRING),
            "condition" to routeDescriptor("condition"),
            "objSink" to descriptor("objSink", inputs = listOf(slot("properties", SlotKind.OBJECT))),
        )
        val keyById = mapOf("s" to "strSource", "cond" to "condition", "sink" to "objSink")
        val edges = listOf(
            PipelineEdge(id = "e1", source = "s", target = "cond", targetPort = "in"),
            PipelineEdge(id = "e2", source = "cond", target = "sink", sourcePort = "true", targetPort = "properties"),
        )
        val v = SlotConnectionValidator.validate(keyById, edges, descriptors)
        assertEquals(1, v.size)
        assertTrue(v.single().contains("expects object but is fed string"), v.single())
    }

    // ---- instance-level declared inputs (HasDeclaredInputs) ----

    /** A source instance declaring its output type (the JSONata/Cast pattern). */
    private class DeclaredOutput(
        override val declaredOutputKind: SlotKind,
        override val declaredOutputType: String = "",
    ) : HasDeclaredOutput

    /** A target instance requiring a specific type on one slot (the email template node pattern). */
    private class DeclaredInputs(vararg pairs: Pair<String, String>) : HasDeclaredInputs {
        override val declaredInputTypes: Map<String, String> = pairs.toMap()
    }

    @Test
    fun `a declared input turns an ANY slot into a pinned slot that refuses unknown sources`() {
        val descriptors = mapOf(
            "jsonata" to descriptor("jsonata", outputKind = SlotKind.ANY),
            "sendEmail" to descriptor("sendEmail", inputs = listOf(slot("payload", SlotKind.ANY, typeLabel = "Payload"))),
        )
        val keyById = mapOf("j" to "jsonata", "e" to "sendEmail")
        val edges = listOf(PipelineEdge(id = "e1", source = "j", target = "e", targetPort = "payload"))
        // Without the declaration the ANY slot accepts anything…
        assertEquals(emptyList(), SlotConnectionValidator.validate(keyById, edges, descriptors))
        // …with it, an unknown source is refused.
        val declaredInputs = mapOf("e" to DeclaredInputs("payload" to "email:transactional/welcome"))
        val v = SlotConnectionValidator.validate(keyById, edges, descriptors, emptyMap(), declaredInputs)
        assertEquals(1, v.size)
        assertTrue(v.single().contains("produces an unknown value"), v.single())
    }

    @Test
    fun `a source declaring the required type satisfies a declared input`() {
        val descriptors = mapOf(
            "cast" to descriptor("cast", outputKind = SlotKind.ANY),
            "sendEmail" to descriptor("sendEmail", inputs = listOf(slot("payload", SlotKind.ANY, typeLabel = "Payload"))),
        )
        val keyById = mapOf("c" to "cast", "e" to "sendEmail")
        val edges = listOf(PipelineEdge(id = "e1", source = "c", target = "e", targetPort = "payload"))
        val declaredOutputs = mapOf("c" to DeclaredOutput(SlotKind.OBJECT, "email:transactional/welcome"))
        val declaredInputs = mapOf("e" to DeclaredInputs("payload" to "email:transactional/welcome"))
        assertEquals(
            emptyList(),
            SlotConnectionValidator.validate(keyById, edges, descriptors, declaredOutputs, declaredInputs),
        )
    }

    @Test
    fun `a source declaring a different contract is refused with the required contract named`() {
        val descriptors = mapOf(
            "cast" to descriptor("cast", outputKind = SlotKind.ANY),
            "sendEmail" to descriptor("sendEmail", inputs = listOf(slot("payload", SlotKind.ANY, typeLabel = "Payload"))),
        )
        val keyById = mapOf("c" to "cast", "e" to "sendEmail")
        val edges = listOf(PipelineEdge(id = "e1", source = "c", target = "e", targetPort = "payload"))
        val declaredOutputs = mapOf("c" to DeclaredOutput(SlotKind.OBJECT, "email:transactional/reset"))
        val declaredInputs = mapOf("e" to DeclaredInputs("payload" to "email:transactional/welcome"))
        val v = SlotConnectionValidator.validate(keyById, edges, descriptors, declaredOutputs, declaredInputs)
        assertEquals(1, v.size)
        assertTrue(v.single().contains("email:transactional/welcome"), v.single())
    }

    @Test
    fun `a declared input leaves the node's other slots untouched`() {
        val descriptors = mapOf(
            "jsonata" to descriptor("jsonata", outputKind = SlotKind.ANY),
            "sendEmail" to descriptor(
                "sendEmail",
                inputs = listOf(slot("recipients", SlotKind.ANY), slot("payload", SlotKind.ANY, typeLabel = "Payload")),
            ),
        )
        val keyById = mapOf("j" to "jsonata", "e" to "sendEmail")
        val edges = listOf(PipelineEdge(id = "e1", source = "j", target = "e", targetPort = "recipients"))
        val declaredInputs = mapOf("e" to DeclaredInputs("payload" to "email:transactional/welcome"))
        assertEquals(
            emptyList(),
            SlotConnectionValidator.validate(keyById, edges, descriptors, emptyMap(), declaredInputs),
        )
    }

    @Test
    fun `blank declared input contracts leave the matched slot unchanged`() {
        val descriptors = mapOf(
            "source" to descriptor("source", outputKind = SlotKind.INTEGER),
            "sink" to descriptor("sink", inputs = listOf(slot("value", SlotKind.INTEGER))),
        )
        val keys = mapOf("s" to "source", "t" to "sink")
        val edges = listOf(PipelineEdge("e", "s", "t", targetPort = "value"))

        assertEquals(
            emptyList(),
            SlotConnectionValidator.validate(
                keys,
                edges,
                descriptors,
                declaredInputs = mapOf("t" to DeclaredInputs("value" to "  ")),
            ),
        )
    }

    @Test
    fun `routing cycles resolve as unknown and remain trusted only for structural slots`() {
        val descriptors = mapOf(
            "route1" to routeDescriptor("route1"),
            "route2" to routeDescriptor("route2"),
            "objectSink" to descriptor("objectSink", inputs = listOf(slot("value", SlotKind.OBJECT))),
            "arraySink" to descriptor("arraySink", inputs = listOf(slot("value", SlotKind.ARRAY))),
            "scalarSink" to descriptor("scalarSink", inputs = listOf(slot("value", SlotKind.STRING))),
        )
        val keys = mapOf(
            "r1" to "route1",
            "r2" to "route2",
            "o" to "objectSink",
            "a" to "arraySink",
            "s" to "scalarSink",
        )
        val cycle = listOf(
            PipelineEdge("c1", "r1", "r2", sourcePort = "yes", targetPort = "in"),
            PipelineEdge("c2", "r2", "r1", sourcePort = "yes", targetPort = "in"),
        )

        assertEquals(
            emptyList(),
            SlotConnectionValidator.validate(
                keys,
                cycle + PipelineEdge("out", "r1", "o", sourcePort = "yes", targetPort = "value"),
                descriptors,
            ),
        )
        assertEquals(
            emptyList(),
            SlotConnectionValidator.validate(
                keys,
                cycle + PipelineEdge("out", "r1", "a", sourcePort = "yes", targetPort = "value"),
                descriptors,
            ),
        )
        assertEquals(
            1,
            SlotConnectionValidator.validate(
                keys,
                cycle + PipelineEdge("out", "r1", "s", sourcePort = "yes", targetPort = "value"),
                descriptors,
            ).size,
        )
    }

    @Test
    fun `an ANY instance declaration falls back to the concrete output port`() {
        val descriptors = mapOf(
            "source" to descriptor("source", outputKind = SlotKind.INTEGER, outputType = "number.Int"),
            "sink" to descriptor("sink", inputs = listOf(slot("value", SlotKind.INTEGER))),
        )
        val keys = mapOf("s" to "source", "t" to "sink")
        val edges = listOf(PipelineEdge("e", "s", "t", targetPort = "value"))

        assertEquals(
            emptyList(),
            SlotConnectionValidator.validate(
                keys,
                edges,
                descriptors,
                declaredOutputs = mapOf("s" to DeclaredOutput(SlotKind.ANY, "")),
            ),
        )
    }

    @Test
    fun `a blank type label falls back to the object kind in a type violation`() {
        val descriptors = mapOf(
            "source" to descriptor("source", outputKind = SlotKind.OBJECT, outputType = "example.Actual"),
            "sink" to descriptor(
                "sink",
                inputs = listOf(slot("value", SlotKind.OBJECT, type = "example.Required", typeLabel = "")),
            ),
        )
        val violations = SlotConnectionValidator.validate(
            mapOf("s" to "source", "t" to "sink"),
            listOf(PipelineEdge("e", "s", "t", targetPort = "value")),
            descriptors,
        )

        assertTrue(violations.single().contains("expects a object"), violations.single())
    }

    @Test
    fun `a blank instance output type falls back to the matched port type`() {
        val descriptors = mapOf(
            "source" to descriptor("source", outputKind = SlotKind.OBJECT, outputType = "example.Required"),
            "sink" to descriptor("sink", inputs = listOf(slot("value", SlotKind.OBJECT, type = "example.Required"))),
        )

        assertEquals(
            emptyList(),
            SlotConnectionValidator.validate(
                mapOf("s" to "source", "t" to "sink"),
                listOf(PipelineEdge("e", "s", "t", targetPort = "value")),
                descriptors,
                declaredOutputs = mapOf("s" to DeclaredOutput(SlotKind.OBJECT, "")),
            ),
        )
    }

    @Test
    fun `an ANY instance kind falls back to the port while retaining its declared type`() {
        val descriptors = mapOf(
            "source" to descriptor("source", outputKind = SlotKind.OBJECT, outputType = "example.PortType"),
            "sink" to descriptor("sink", inputs = listOf(slot("value", SlotKind.OBJECT, type = "example.InstanceType"))),
        )

        assertEquals(
            emptyList(),
            SlotConnectionValidator.validate(
                mapOf("s" to "source", "t" to "sink"),
                listOf(PipelineEdge("e", "s", "t", targetPort = "value")),
                descriptors,
                declaredOutputs = mapOf("s" to DeclaredOutput(SlotKind.ANY, "example.InstanceType")),
            ),
        )
    }

    @Test
    fun `an instance declaration supplies output metadata without a source descriptor`() {
        val descriptors = mapOf(
            "sink" to descriptor("sink", inputs = listOf(slot("value", SlotKind.OBJECT, type = "example.Required"))),
        )

        assertEquals(
            emptyList(),
            SlotConnectionValidator.validate(
                mapOf("s" to "missing", "t" to "sink"),
                listOf(PipelineEdge("e", "s", "t", targetPort = "value")),
                descriptors,
                declaredOutputs = mapOf("s" to DeclaredOutput(SlotKind.OBJECT, "example.Required")),
            ),
        )
    }
}
