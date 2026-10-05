package bosca.pipelines.git

import bosca.pipelines.builtin.JsonataNode
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineEdge
import bosca.pipelines.model.PipelineGraph
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.OutputNode
import bosca.pipelines.node.PipelineNode
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Validates that a pipeline and its polymorphic node graph round-trip through the YAML codec:
 * [PipelineRepoFileSerializer] → [PipelineRepoFileParser] → the same graph JSON the engine decodes.
 */
class PipelineRepoFileCodecTest {

    private val serializer = PipelineRepoFileSerializer()
    private val parser = PipelineRepoFileParser()

    private val json = Json {
        encodeDefaults = false
        serializersModule = SerializersModule {
            polymorphic(PipelineNode::class) {
                subclass(InputNode::class, InputNode.serializer())
                subclass(OutputNode::class, OutputNode.serializer())
                subclass(JsonataNode::class, JsonataNode.serializer())
            }
        }
    }

    private fun pipeline(triggered: Boolean = true) = Pipeline(
        id = Uuid.random(),
        name = "Extract Email",
        description = "Extracts the email field",
        acceptedInputType = "Person",
        triggered = triggered,
        nodes = listOf(
            InputNode(id = "in", acceptedType = "Person"),
            JsonataNode(id = "jx", expression = "email"),
            OutputNode(id = "out"),
        ),
        edges = listOf(PipelineEdge("e1", "in", "jx"), PipelineEdge("e2", "jx", "out")),
    )

    private fun graphOf(p: Pipeline) =
        json.encodeToJsonElement(PipelineGraph.serializer(), PipelineGraph(p.nodes, p.edges))

    // --- serializer ---

    @Test
    fun `serialized pipeline contains metadata and node discriminators`() {
        val p = pipeline()
        val text = serializer.serialize(p, graphOf(p))

        assertTrue(text.contains("name: Extract Email"))
        assertTrue(text.contains("accepted_input_type: Person"))
        assertTrue(text.contains("triggered: true"))
        assertTrue(text.contains("description: Extracts the email field"))
        assertTrue(text.contains("type: jsonata"))
        assertTrue(text.contains("expression: email"))
        assertTrue(text.contains("nodes:"))
        assertTrue(text.contains("edges:"))
    }

    @Test
    fun `serialized pipeline omits empty description`() {
        val p = pipeline().copy(description = "")
        val text = serializer.serialize(p, graphOf(p))
        assertTrue(!text.contains("description:"))
    }

    // --- round-trip ---

    @Test
    fun `pipeline round-trips through YAML back to the same decodable graph`() {
        val p = pipeline()
        val text = serializer.serialize(p, graphOf(p))

        val parsed = parser.parse("pipelines/extract-email.yaml", text)
        val file = assertIs<ParsedPipelineFile.Parsed>(parsed).file

        assertEquals("Extract Email", file.name)
        assertEquals("Extracts the email field", file.description)
        assertEquals("Person", file.acceptedInputType)
        assertEquals(true, file.triggered)

        val graph = json.decodeFromJsonElement(PipelineGraph.serializer(), file.graph)
        assertEquals(3, graph.nodes.size)
        assertIs<InputNode>(graph.nodes[0])
        val jx = assertIs<JsonataNode>(graph.nodes[1])
        assertEquals("email", jx.expression)
        assertIs<OutputNode>(graph.nodes[2])
        assertEquals(listOf("e1", "e2"), graph.edges.map { it.id })
        assertEquals("in", graph.edges[0].source)
        assertEquals("jx", graph.edges[0].target)
    }

    @Test
    fun `endpoint exposure round-trips through YAML and defaults to off when absent`() {
        val exposed = pipeline().copy(key = "extract-email", api = true, public = true)
        val text = serializer.serialize(exposed, graphOf(exposed))
        assertTrue(text.contains("key: extract-email"))
        assertTrue(text.contains("api: true"))
        assertTrue(text.contains("public: true"))

        val file = assertIs<ParsedPipelineFile.Parsed>(parser.parse("pipelines/extract-email.yaml", text)).file
        assertEquals("extract-email", file.key)
        assertEquals(true, file.api)
        assertEquals(true, file.public)

        // A pipeline without endpoint exposure writes none of the fields, and parsing defaults them off.
        val plainText = serializer.serialize(pipeline(), graphOf(pipeline()))
        assertTrue(!plainText.contains("key:"))
        assertTrue(!plainText.contains("api:"))
        assertTrue(!plainText.contains("public:"))
        val plain = assertIs<ParsedPipelineFile.Parsed>(parser.parse("pipelines/extract-email.yaml", plainText)).file
        assertEquals("", plain.key)
        assertEquals(false, plain.api)
        assertEquals(false, plain.public)
    }

    @Test
    fun `schedule round-trips through repository YAML`() {
        val scheduled = pipeline().copy(schedule = "0 */15 * * * ?")
        val text = serializer.serialize(scheduled, graphOf(scheduled))

        assertTrue(text.contains("schedule: 0 */15 * * * ?"))
        val file = assertIs<ParsedPipelineFile.Parsed>(parser.parse("pipelines/scheduled.yaml", text)).file
        assertEquals("0 */15 * * * ?", file.schedule)
        val unscheduled = assertIs<ParsedPipelineFile.Parsed>(
            parser.parse("pipelines/manual.yaml", "name: Manual\naccepted_input_type: JSON\n"),
        ).file
        assertEquals(null, unscheduled.schedule)
    }

    @Test
    fun `tags round-trip and a blank schedule is omitted`() {
        val tagged = pipeline().copy(tags = listOf("release", "nightly"), schedule = "   ")
        val text = serializer.serialize(tagged, graphOf(tagged))

        assertTrue(text.contains("tags:"))
        assertTrue(text.contains("- release"))
        assertTrue(!text.contains("schedule:"))
        val file = assertIs<ParsedPipelineFile.Parsed>(parser.parse("pipelines/tagged.yaml", text)).file
        assertEquals(listOf("release", "nightly"), file.tags)
        assertNull(file.schedule)
    }

    // --- parser errors ---

    @Test
    fun `parse rejects a file missing required fields`() {
        val parsed = parser.parse("pipelines/p.yaml", "description: no name here\n")
        val error = assertIs<ParsedPipelineFile.ParseError>(parsed)
        assertTrue(error.message.contains("name"))
        assertTrue(error.message.contains("accepted_input_type"))
    }

    @Test
    fun `parse rejects malformed YAML`() {
        val parsed = parser.parse("pipelines/p.yaml", "name: [unclosed\n  - broken")
        assertIs<ParsedPipelineFile.ParseError>(parsed)
    }

    @Test
    fun `parse rejects a non-mapping document`() {
        val parsed = parser.parse("pipelines/p.yaml", "- just\n- a\n- list\n")
        val error = assertIs<ParsedPipelineFile.ParseError>(parsed)
        assertTrue(error.message.contains("mapping"))
    }

    @Test
    fun `parse rejects wrongly typed fields`() {
        val parsed = parser.parse(
            "pipelines/p.yaml",
            "name: P\naccepted_input_type: T\ntriggered: maybe\nnodes: not-a-list\n"
        )
        val error = assertIs<ParsedPipelineFile.ParseError>(parsed)
        assertTrue(error.message.contains("triggered"))
        assertTrue(error.message.contains("nodes"))
    }

    @Test
    fun `parse ignores files outside the pipelines directory or with other extensions`() {
        assertIs<ParsedPipelineFile.UnknownPath>(parser.parse("docs/readme.yaml", "name: x"))
        assertIs<ParsedPipelineFile.UnknownPath>(parser.parse("pipelines/README.md", "# readme"))
    }

    @Test
    fun `parse accepts both yaml and yml extensions`() {
        val content = "name: P\naccepted_input_type: T\n"
        assertIs<ParsedPipelineFile.Parsed>(parser.parse("pipelines/p.yaml", content))
        assertIs<ParsedPipelineFile.Parsed>(parser.parse("pipelines/p.yml", content))
    }

    @Test
    fun `parse defaults triggered to false and nodes-edges to empty`() {
        val parsed = parser.parse("pipelines/p.yaml", "name: P\naccepted_input_type: T\n")
        val file = assertIs<ParsedPipelineFile.Parsed>(parsed).file
        assertEquals(false, file.triggered)
        val graph = json.decodeFromJsonElement(PipelineGraph.serializer(), file.graph)
        assertTrue(graph.nodes.isEmpty())
        assertTrue(graph.edges.isEmpty())
    }

    // --- parser: empty / non-mapping documents ---

    @Test
    fun `parse rejects an empty document as a non-mapping`() {
        // Yaml().load("") returns null -> the elvis arm produces the "must be a YAML mapping" error.
        val parsed = parser.parse("pipelines/p.yaml", "")
        val error = assertIs<ParsedPipelineFile.ParseError>(parsed)
        assertTrue(error.message.contains("must be a YAML mapping"))
    }

    @Test
    fun `parse reports the malformed-YAML message including the YAMLException detail`() {
        val parsed = parser.parse("pipelines/p.yaml", "name: \"unterminated")
        val error = assertIs<ParsedPipelineFile.ParseError>(parsed)
        assertTrue(error.message.contains("malformed YAML"))
    }

    // --- parser: required-field wrong type (requireString else arm) ---

    @Test
    fun `parse rejects required name that is not a string`() {
        val parsed = parser.parse("pipelines/p.yaml", "name: 42\naccepted_input_type: T\n")
        val error = assertIs<ParsedPipelineFile.ParseError>(parsed)
        assertTrue(error.message.contains("field 'name' must be a string"))
        // Captures the got-type clause too (Integer).
        assertTrue(error.message.contains("got"))
    }

    @Test
    fun `parse rejects required accepted_input_type that is not a string`() {
        val parsed = parser.parse("pipelines/p.yaml", "name: P\naccepted_input_type: true\n")
        val error = assertIs<ParsedPipelineFile.ParseError>(parsed)
        assertTrue(error.message.contains("field 'accepted_input_type' must be a string"))
    }

    // --- parser: optional-field wrong types (optionalString/Boolean/List else arms) ---

    @Test
    fun `parse rejects a non-string description`() {
        val parsed = parser.parse(
            "pipelines/p.yaml",
            "name: P\naccepted_input_type: T\ndescription: 123\n"
        )
        val error = assertIs<ParsedPipelineFile.ParseError>(parsed)
        assertTrue(error.message.contains("field 'description' must be a string"))
    }

    @Test
    fun `parse rejects a non-string key`() {
        val parsed = parser.parse(
            "pipelines/p.yaml",
            "name: P\naccepted_input_type: T\nkey: 7\n"
        )
        val error = assertIs<ParsedPipelineFile.ParseError>(parsed)
        assertTrue(error.message.contains("field 'key' must be a string"))
    }

    @Test
    fun `parse rejects a non-boolean api`() {
        val parsed = parser.parse(
            "pipelines/p.yaml",
            "name: P\naccepted_input_type: T\napi: yes-please\n"
        )
        val error = assertIs<ParsedPipelineFile.ParseError>(parsed)
        assertTrue(error.message.contains("field 'api' must be a boolean"))
    }

    @Test
    fun `parse rejects a non-boolean public`() {
        val parsed = parser.parse(
            "pipelines/p.yaml",
            "name: P\naccepted_input_type: T\npublic: 3\n"
        )
        val error = assertIs<ParsedPipelineFile.ParseError>(parsed)
        assertTrue(error.message.contains("field 'public' must be a boolean"))
    }

    @Test
    fun `parse rejects a non-list edges`() {
        val parsed = parser.parse(
            "pipelines/p.yaml",
            "name: P\naccepted_input_type: T\nedges: not-a-list\n"
        )
        val error = assertIs<ParsedPipelineFile.ParseError>(parsed)
        assertTrue(error.message.contains("field 'edges' must be a list"))
    }

    @Test
    fun `parse accepts string tags and rejects non-list or non-string tags`() {
        val valid = assertIs<ParsedPipelineFile.Parsed>(
            parser.parse(
                "pipelines/p.yaml",
                "name: P\naccepted_input_type: T\ntags:\n- release\n- nightly\n",
            ),
        ).file
        assertEquals(listOf("release", "nightly"), valid.tags)

        val notAList = assertIs<ParsedPipelineFile.ParseError>(
            parser.parse("pipelines/p.yaml", "name: P\naccepted_input_type: T\ntags: release\n"),
        )
        assertTrue(notAList.message.contains("field 'tags' must be a list"))

        val invalidEntries = assertIs<ParsedPipelineFile.ParseError>(
            parser.parse(
                "pipelines/p.yaml",
                "name: P\naccepted_input_type: T\ntags:\n- release\n- 7\n- null\n",
            ),
        )
        assertTrue(invalidEntries.message.contains("entries must be strings"))
        assertTrue(invalidEntries.message.contains("Int"))
        assertTrue(invalidEntries.message.contains("null"))
    }

    // --- parser: toJsonElement across every scalar/collection arm ---

    @Test
    fun `parse coerces every YAML scalar and nested collection into JSON`() {
        val content = """
            name: P
            accepted_input_type: T
            nodes:
            - id: n1
              type: jsonata
              count: 7
              big: 9999999999
              ratio: 1.5
              enabled: true
              note: hello
              missing: null
              tags:
              - a
              - b
              meta:
                k: v
                deep:
                  flag: false
        """.trimIndent() + "\n"
        val parsed = parser.parse("pipelines/p.yaml", content)
        val file = assertIs<ParsedPipelineFile.Parsed>(parsed).file
        val nodes = assertIs<JsonArray>((file.graph as JsonObject)["nodes"])
        val node = assertIs<JsonObject>(nodes.single())
        // Int
        assertEquals(7L, assertIs<JsonPrimitive>(node["count"]).content.toLong())
        // Long
        assertEquals(9999999999L, assertIs<JsonPrimitive>(node["big"]).content.toLong())
        // Double
        assertEquals(1.5, assertIs<JsonPrimitive>(node["ratio"]).content.toDouble())
        // Boolean
        assertEquals("true", assertIs<JsonPrimitive>(node["enabled"]).content)
        // String
        assertEquals("hello", assertIs<JsonPrimitive>(node["note"]).content)
        // null -> JsonNull
        assertEquals(JsonNull, node["missing"])
        // nested List
        val tags = assertIs<JsonArray>(node["tags"])
        assertEquals(2, tags.size)
        // nested Map (and a Map within a Map)
        val meta = assertIs<JsonObject>(node["meta"])
        assertEquals("v", assertIs<JsonPrimitive>(meta["k"]).content)
        val deep = assertIs<JsonObject>(meta["deep"])
        assertEquals("false", assertIs<JsonPrimitive>(deep["flag"]).content)
    }

    @Test
    fun `parse coerces a YAML integer beyond Long range through the generic Number arm`() {
        // SnakeYAML parses an integer larger than Long.MAX as a java.math.BigInteger, which is not
        // Int/Long/Double/Float but IS a Number -> the `is Number -> JsonPrimitive(value.toDouble())`
        // arm of toJsonElement (L132).
        val content = "name: P\naccepted_input_type: T\nnodes:\n- id: n1\n  huge: 99999999999999999999999999999999\n"
        val parsed = parser.parse("pipelines/p.yaml", content)
        val file = assertIs<ParsedPipelineFile.Parsed>(parsed).file
        val nodes = assertIs<JsonArray>((file.graph as JsonObject)["nodes"])
        val node = assertIs<JsonObject>(nodes.single())
        // The BigInteger was widened to a Double primitive.
        val huge = assertIs<JsonPrimitive>(node["huge"])
        assertEquals(1.0E32, huge.content.toDouble())
    }

    @Test
    fun `parse falls back to the string form for a YAML value of an unrecognized type`() {
        // SnakeYAML parses a bare ISO date as a java.util.Date, which matches none of the scalar /
        // Map / List arms -> the `else -> JsonPrimitive(value.toString())` fallback (L135), reached by
        // the `is List<*>` arm of toJsonElement evaluating false (L134).
        val content = "name: P\naccepted_input_type: T\nnodes:\n- id: n1\n  when: 2020-01-01\n"
        val parsed = parser.parse("pipelines/p.yaml", content)
        val file = assertIs<ParsedPipelineFile.Parsed>(parsed).file
        val nodes = assertIs<JsonArray>((file.graph as JsonObject)["nodes"])
        val node = assertIs<JsonObject>(nodes.single())
        // The Date was stringified via toString(); we only assert a non-empty string survived.
        val whenField = assertIs<JsonPrimitive>(node["when"])
        assertTrue(whenField.isString)
        assertTrue(whenField.content.contains("2020") || whenField.content.contains("2019"), whenField.content)
    }

    @Test
    fun `parse coerces a non-string mapping key to its string form`() {
        // Integer mapping keys exercise the Map branch's k.toString() in toJsonElement.
        val content = "name: P\naccepted_input_type: T\nnodes:\n- 1: one\n  2: two\n"
        val parsed = parser.parse("pipelines/p.yaml", content)
        val file = assertIs<ParsedPipelineFile.Parsed>(parsed).file
        val nodes = assertIs<JsonArray>((file.graph as JsonObject)["nodes"])
        val node = assertIs<JsonObject>(nodes.single())
        assertEquals("one", assertIs<JsonPrimitive>(node["1"]).content)
        assertEquals("two", assertIs<JsonPrimitive>(node["2"]).content)
    }

    // --- serializer: jsonElementToYaml across primitive/null/array arms + endpoint flags off vs on ---

    @Test
    fun `serialize round-trips primitive scalars and JsonNull in the graph`() {
        val p = pipeline()
        val graph = JsonObject(
            mapOf(
                "nodes" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive("n1"),
                                "type" to JsonPrimitive("jsonata"),
                                "flag" to JsonPrimitive(true),
                                "count" to JsonPrimitive(42L),
                                "ratio" to JsonPrimitive(3.25),
                                "label" to JsonPrimitive("hi"),
                                "nothing" to JsonNull,
                                "nested" to JsonArray(listOf(JsonPrimitive("x"), JsonPrimitive(1L))),
                            )
                        )
                    )
                ),
                "edges" to JsonArray(emptyList()),
            )
        )
        val text = serializer.serialize(p, graph)
        // Boolean, long, double, string survive the YAML dump.
        assertTrue(text.contains("flag: true"))
        assertTrue(text.contains("count: 42"))
        assertTrue(text.contains("ratio: 3.25"))
        assertTrue(text.contains("label: hi"))

        // It must parse back to a decodable structure (round-trip closure on these arms).
        val file = assertIs<ParsedPipelineFile.Parsed>(parser.parse("pipelines/p.yaml", text)).file
        val nodes = assertIs<JsonArray>((file.graph as JsonObject)["nodes"])
        assertEquals(1, nodes.size)
    }

    @Test
    fun `serialize falls back to empty graph when the graph element is not a JSON object`() {
        // jsonElementToYaml(graph) is an array here -> `as? Map<*, *>` is null -> emptyMap fallback,
        // so nodes/edges come from the `?: emptyList()` arms.
        val p = pipeline()
        val text = serializer.serialize(p, JsonArray(emptyList()))
        assertTrue(text.contains("name: Extract Email"))
        // SnakeYAML's pretty BLOCK flow renders an empty sequence as `nodes: [` / `edges: [` over two
        // lines; the fallback therefore yields empty nodes/edges, confirmed by a round-trip parse.
        assertTrue(text.contains("nodes:"))
        assertTrue(text.contains("edges:"))
        val parsed = assertIs<ParsedPipelineFile.Parsed>(parser.parse("pipelines/p.yaml", text)).file
        val graph = json.decodeFromJsonElement(PipelineGraph.serializer(), parsed.graph)
        assertTrue(graph.nodes.isEmpty())
        assertTrue(graph.edges.isEmpty())
    }

    @Test
    fun `serialize uses empty lists when the graph object lacks nodes and edges keys`() {
        // graph is a JsonObject but with neither "nodes" nor "edges" -> both elvis arms fire.
        val p = pipeline()
        val text = serializer.serialize(p, JsonObject(mapOf("other" to JsonPrimitive("v"))))
        assertTrue(text.contains("nodes:"))
        assertTrue(text.contains("edges:"))
        // The unknown "other" key is not carried into the document; nodes/edges parse back empty.
        assertTrue(!text.contains("other:"))
        val parsed = assertIs<ParsedPipelineFile.Parsed>(parser.parse("pipelines/p.yaml", text)).file
        val graph = json.decodeFromJsonElement(PipelineGraph.serializer(), parsed.graph)
        assertTrue(graph.nodes.isEmpty())
        assertTrue(graph.edges.isEmpty())
    }

    @Test
    fun `serialize omits endpoint fields when they are at their defaults`() {
        // key empty, api false, public false -> none of the three `if` blocks add a field.
        val p = pipeline().copy(key = "", api = false, public = false)
        val text = serializer.serialize(p, graphOf(p))
        assertTrue(!text.contains("key:"))
        assertTrue(!text.contains("api:"))
        assertTrue(!text.contains("public:"))
    }

    @Test
    fun `serialize writes only the endpoint fields that are set`() {
        // key set but api/public false -> only `key` is emitted.
        val p = pipeline().copy(key = "k1", api = false, public = false)
        val text = serializer.serialize(p, graphOf(p))
        assertTrue(text.contains("key: k1"))
        assertTrue(!text.contains("api:"))
        assertTrue(!text.contains("public:"))
    }

    // --- PipelineFile data class: round-trip + per-field equality arms ---

    private fun sampleFile() = PipelineFile(
        path = "pipelines/a.yaml",
        name = "A",
        description = "d",
        acceptedInputType = "T",
        tags = listOf("release", "nightly"),
        triggered = true,
        key = "k",
        api = true,
        public = true,
        schedule = "0 0 * * * ?",
        graph = JsonObject(mapOf("nodes" to JsonArray(emptyList()), "edges" to JsonArray(emptyList()))),
    )

    @Test
    fun `PipelineFile equals is identity over itself and an equal copy`() {
        val f = sampleFile()
        assertEquals(f, f.copy())
        assertEquals(f.hashCode(), f.copy().hashCode())
    }

    @Test
    fun `PipelineFile equals differs when any single field differs`() {
        val f = sampleFile()
        assertNotEquals(f, f.copy(path = "pipelines/b.yaml"))
        assertNotEquals(f, f.copy(name = "B"))
        assertNotEquals(f, f.copy(description = "other"))
        assertNotEquals(f, f.copy(acceptedInputType = "U"))
        assertNotEquals(f, f.copy(triggered = false))
        assertNotEquals(f, f.copy(key = "k2"))
        assertNotEquals(f, f.copy(api = false))
        assertNotEquals(f, f.copy(public = false))
        assertNotEquals(f, f.copy(graph = JsonNull))
    }

    // --- ParsedPipelineFile sealed subtypes: equality / toString smoke ---

    @Test
    fun `ParsedPipelineFile subtypes carry their payloads`() {
        val parsed = ParsedPipelineFile.Parsed(sampleFile())
        assertEquals(sampleFile(), parsed.file)
        val err = ParsedPipelineFile.ParseError("pipelines/a.yaml", "boom")
        assertEquals("pipelines/a.yaml", err.path)
        assertEquals("boom", err.message)
        val unknown = ParsedPipelineFile.UnknownPath("docs/x.txt")
        assertEquals("docs/x.txt", unknown.path)
        assertNotEquals<ParsedPipelineFile>(parsed, err)
    }

    // --- serializer: jsonElementToYaml non-string primitive coercion chain (long / content fallback) ---

    @Test
    fun `serialize coerces an integer-valued primitive through the longOrNull arm`() {
        // A non-string, non-boolean primitive whose longOrNull is non-null takes the `?: element.longOrNull`
        // arm (the boolean attempt is null, so the long wins before double/content are tried).
        val p = pipeline()
        val graph = JsonObject(
            mapOf(
                "nodes" to JsonArray(
                    listOf(JsonObject(mapOf("id" to JsonPrimitive("n1"), "n" to JsonPrimitive(123L))))
                ),
                "edges" to JsonArray(emptyList()),
            )
        )
        val text = serializer.serialize(p, graph)
        assertTrue(text.contains("n: 123"))
        // Round-trips back to a long, confirming the long arm rendered a bare number (not a quoted string).
        val file = assertIs<ParsedPipelineFile.Parsed>(parser.parse("pipelines/p.yaml", text)).file
        val node = assertIs<JsonObject>(assertIs<JsonArray>((file.graph as JsonObject)["nodes"]).single())
        assertEquals(123L, assertIs<JsonPrimitive>(node["n"]).content.toLong())
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `serialize falls back to raw content for a non-numeric, non-boolean unquoted primitive`() {
        // An unquoted literal that is neither boolean nor long nor double exhausts the chain and hits
        // the final `?: element.content` fallback, emitting the literal text verbatim.
        val p = pipeline()
        val graph = JsonObject(
            mapOf(
                "nodes" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive("n1"),
                                // not a string (isString=false), and booleanOrNull/longOrNull/doubleOrNull are all null
                                "weird" to JsonUnquotedLiteral("0x1f"),
                            )
                        )
                    )
                ),
                "edges" to JsonArray(emptyList()),
            )
        )
        val text = serializer.serialize(p, graph)
        assertTrue(text.contains("0x1f"))
    }
}
