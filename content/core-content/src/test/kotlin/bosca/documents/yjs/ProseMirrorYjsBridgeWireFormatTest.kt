package bosca.documents.yjs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * Cross-runtime wire-format check.
 *
 * Each fixture is produced by `web/projects/studio/scripts/gen-yjs-fixtures.mjs`
 * which uses the actual `y-prosemirror` library to encode a known PM document
 * into a Yjs binary update. This test feeds that binary into [ProseMirrorYjsBridge]
 * and asserts the decoded ProseMirror JSON tree matches the source — proving
 * that the JVM bridge consumes the exact wire format JS callers produce.
 *
 * If a fixture moves out of sync with the bridge (e.g. y-prosemirror changes how
 * a particular node's attrs are encoded), this catches it as a structural diff
 * with a clear message instead of a silent shape drift in production.
 *
 * To regenerate fixtures after y-prosemirror or schema changes:
 * `cd web/projects/studio && node scripts/gen-yjs-fixtures.mjs`
 */
class ProseMirrorYjsBridgeWireFormatTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `marks with attrs encode as JSON-parseable strings on the wire`() {
        // The Kotlin↔JS interop for `ContentFormat` values is delicate: yks's writeJSON
        // is just `writeVarString(str)` (does NOT call JSON.stringify), while JS yjs's
        // writeJSON does `writeVarString(JSON.stringify(value))`. The two formats happen
        // to interoperate ONLY because:
        //   • the bridge passes pre-JSON-encoded strings (`JsonObject.toString()`)
        //   • Boolean.toString() in Kotlin produces "true"/"false" — valid JSON literals
        //   • the corresponding JS values JSON.stringify to the same bytes
        //
        // This test pins those invariants. If anyone ever:
        //   • changes the bridge to pass a raw Kotlin Map to YText format attrs (Map.toString
        //     produces `{href=...}`, not JSON), or
        //   • changes yks's `ContentFormat.write` to actually JSON.stringify (matching the
        //     JS API but causing the bridge's pre-encoded string to be double-encoded), or
        //   • changes yks's writeJSON wire encoding,
        // then this test fails and the JS editor would silently lose attrs on next decode.
        val link = bosca.documents.marks.Link(
            attributes = bosca.documents.marks.LinkAttributes(href = "https://x.test", target = "_blank")
        )
        val original = bosca.documents.Content(document = bosca.documents.Document(content = listOf(
            bosca.documents.ParagraphNode(content = listOf(
                bosca.documents.TextNode(text = "click", marks = listOf(link))
            ))
        )))

        val update = ProseMirrorYjsBridge.toYDocUpdate(original)

        // Replay the binary back through yks to inspect the wire-level format value.
        val doc = yks.utils.Doc()
        yks.utils.applyUpdate(doc, update)
        val frag = doc.getXmlFragment("default")
        val para = frag.get(0) as yks.types.YXmlElement
        val ytext = para.get(0) as yks.types.YXmlText

        // toDelta exposes the ContentFormat value as-is — for marks-with-attrs, that's
        // a String holding JSON. JS reads the same varString and JSON.parses it; if our
        // value is JSON-parseable to the original attrs object, JS round-trip works.
        val deltas = ytext.toDelta()
        val attrs = deltas.firstOrNull { it["attributes"] != null }
            ?.let { @Suppress("UNCHECKED_CAST") (it["attributes"] as Map<String, Any?>) }
            ?: fail("expected at least one delta segment with attributes; got $deltas")

        val linkValue = attrs["link"] ?: fail("expected `link` attr in delta segment, got keys ${attrs.keys}")
        val linkValueStr = linkValue as? String
            ?: fail("link value must be a String holding JSON (the wire-format contract); got ${linkValue::class.simpleName} = $linkValue")

        // Now JSON-parse it the way JS y-prosemirror would.
        val parsed = Json.parseToJsonElement(linkValueStr) as? JsonObject
            ?: fail("link wire value must JSON-parse to an object — got $linkValueStr")
        assertEquals("https://x.test", parsed["href"]?.jsonPrimitive?.contentOrNull, "href round-trips")
        assertEquals("_blank", parsed["target"]?.jsonPrimitive?.contentOrNull, "target round-trips")
    }

    @Test
    fun `boolean marks encode as the literal strings true and false on the wire`() {
        // Simple marks (bold, italic, strike, …) have value `true` and round-trip through
        // ContentFormat as the string "true" because Boolean.toString() in Kotlin produces
        // the literal "true". JS y-prosemirror reads the same varString and JSON.parses it
        // to the boolean — works only because the strings happen to be valid JSON literals.
        // A regression where Kotlin starts producing "True" or "1" would silently break JS
        // decode without any other test catching it.
        val original = bosca.documents.Content(document = bosca.documents.Document(content = listOf(
            bosca.documents.ParagraphNode(content = listOf(
                bosca.documents.TextNode(text = "loud", marks = listOf(bosca.documents.marks.Bold())),
            ))
        )))

        val update = ProseMirrorYjsBridge.toYDocUpdate(original)
        val doc = yks.utils.Doc()
        yks.utils.applyUpdate(doc, update)
        val frag = doc.getXmlFragment("default")
        val para = frag.get(0) as yks.types.YXmlElement
        val ytext = para.get(0) as yks.types.YXmlText
        val deltas = ytext.toDelta()
        val attrs = deltas.firstOrNull { it["attributes"] != null }
            ?.let { @Suppress("UNCHECKED_CAST") (it["attributes"] as Map<String, Any?>) }
            ?: fail("expected attributes in delta; got $deltas")

        val boldValue = attrs["bold"] ?: fail("bold mark missing")
        // The wire-format invariant: bold's value is either the literal Boolean true OR
        // the String "true". Both decode in JS to boolean true via JSON.parse.
        val isWireCompatible = boldValue == true || boldValue == "true"
        assertNotNull(boldValue.takeIf { isWireCompatible },
            "bold mark must be `true` or the string \"true\" on the wire — got ${boldValue::class.simpleName} = $boldValue")
    }

    @Test fun `paragraph wire-format`() = check("paragraph")
    @Test fun `heading-with-level wire-format`() = check("heading-with-level")
    @Test fun `marks bold italic link wire-format`() = check("marks-bold-italic-link")
    @Test fun `inline code and strike wire-format`() = check("inline-code-and-strike")
    @Test fun `bullet list wire-format`() = check("bullet-list")
    @Test fun `nested bullet list wire-format`() = check("nested-bullet-list")
    @Test fun `task list wire-format`() = check("task-list")
    @Test fun `code block with language wire-format`() = check("code-block-with-language")
    @Test fun `table wire-format`() = check("table")

    private fun check(fixture: String) {
        val bin = readBinary("$fixture.bin")
        val expectedDoc = json.parseToJsonElement(readText("$fixture.json"))
        val actualDoc = ProseMirrorYjsBridge.toProseMirrorJson(bin)
        // Normalize ONLY to bridge-the-gap on cosmetics that don't affect rendering
        // (empty containers and missing-vs-explicit-null). We deliberately do NOT strip
        // default-valued attributes here — `colspan: 1`, `start: 1`, etc. must match
        // exactly between the y-prosemirror source and the bridge output. Past versions
        // of this test stripped those defaults and silently allowed the bridge to drop
        // numeric attributes by emitting them as strings; that drift is now fail-fast.
        val normalizedExpected = normalize(expectedDoc)
        val normalizedActual = normalize(actualDoc)
        if (normalizedExpected != normalizedActual) {
            fail(
                "Fixture `$fixture` decoded to a different shape than the source PM JSON.\n" +
                    "Expected: $normalizedExpected\nActual:   $normalizedActual"
            )
        }
    }

    /**
     * Strip *only* representation cosmetics:
     * - omit empty `attrs: {}`, `content: []`, `marks: []` containers (the bridge never
     *   emits these but y-prosemirror sometimes does)
     * - normalize JsonNull to "missing" — `{x: null}` and `{}` are semantically the same
     *   for ProseMirror schemas
     *
     * Attribute *values* are compared verbatim — `colspan: 1` vs `colspan: "1"` will
     * fail the test, which is exactly what we want.
     */
    private fun normalize(node: JsonElement): JsonElement {
        if (node is JsonArray) return JsonArray(node.map { normalize(it) })
        if (node !is JsonObject) return node
        val out = mutableMapOf<String, JsonElement>()
        for ((k, v) in node) {
            when (k) {
                "attrs", "content", "marks" -> {
                    val container = normalize(v)
                    val isEmpty = (container is JsonObject && container.isEmpty()) ||
                        (container is JsonArray && container.isEmpty())
                    if (!isEmpty) out[k] = container
                }
                else -> {
                    // Drop explicit-null attribute values — `{x: null}` and `{}` are
                    // semantically equivalent for ProseMirror, and the bridge omits
                    // absent attributes.
                    if (v !is kotlinx.serialization.json.JsonNull) {
                        out[k] = normalize(v)
                    }
                }
            }
        }
        return JsonObject(out)
    }

    private fun readBinary(name: String): ByteArray {
        val stream = javaClass.getResourceAsStream("/yjs-fixtures/$name")
        assertNotNull(stream, "fixture $name missing — run `node web/projects/studio/scripts/gen-yjs-fixtures.mjs` to generate")
        return stream.use { it.readAllBytes() }
    }

    private fun readText(name: String): String {
        val stream = javaClass.getResourceAsStream("/yjs-fixtures/$name")
        assertNotNull(stream, "fixture $name missing")
        return stream.use { it.bufferedReader().readText() }
    }
}
