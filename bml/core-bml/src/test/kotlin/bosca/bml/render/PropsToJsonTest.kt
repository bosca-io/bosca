package bosca.bml.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PropsToJsonTest {

    @Test
    fun `primitives and strings`() {
        assertEquals(
            """{"n":3,"flag":true,"label":"hi","nothing":null}""",
            propsToJson(linkedMapOf("n" to 3, "flag" to true, "label" to "hi", "nothing" to null)),
        )
    }

    @Test
    fun `strings are JSON-escaped`() {
        assertEquals("""{"q":"a\"b\\c\nd"}""", propsToJson(mapOf("q" to "a\"b\\c\nd")))
    }

    @Test
    fun `lists and nested maps`() {
        assertEquals(
            """{"items":["a","b"],"nested":{"x":1}}""",
            propsToJson(linkedMapOf("items" to listOf("a", "b"), "nested" to mapOf("x" to 1))),
        )
    }

    @Test
    fun `non-finite doubles become null`() {
        assertEquals("""{"d":null}""", propsToJson(mapOf("d" to Double.NaN)))
    }

    @Test
    fun `unknown types fall back to toString`() {
        val value = object {
            override fun toString() = "custom"
        }
        assertEquals("""{"v":"custom"}""", propsToJson(mapOf("v" to value)))
    }

    @Test
    fun `deferred props accept only JSON-shaped values`() {
        assertEquals(
            """{"id":"42","flags":{"${'$'}bml":"boolean-array","value":[true,false]},"nested":{"${'$'}bml":"map","value":[["count",2]]}}""",
            deferredPropsToJson(
                linkedMapOf(
                    "id" to "42",
                    "flags" to booleanArrayOf(true, false),
                    "nested" to mapOf("count" to 2),
                ),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            deferredPropsToJson(mapOf("value" to Any()))
        }
        assertFailsWith<IllegalArgumentException> {
            deferredPropsToJson(mapOf("value" to Double.NaN))
        }
        assertFailsWith<IllegalArgumentException> {
            deferredPropsToJson(mapOf("value" to setOf("not", "ordered")))
        }
    }

    @Test
    fun `deferred map order survives a JavaScript round trip`() {
        // JavaScript objects move integer-like keys first; pairs keep the author's order.
        val encoded = deferredPropsToJson(mapOf("ids" to linkedMapOf("10" to "ten", "2" to "two", "b" to "bee")))
        assertEquals("""{"ids":{"${'$'}bml":"map","value":[["10","ten"],["2","two"],["b","bee"]]}}""", encoded)

        val request = parseDeferredRenderRequest("""{"props":$encoded,"page":"/","path":"/","query":{}}""")
        assertEquals(listOf("10", "2", "b"), (request?.props?.get("ids") as Map<*, *>).keys.toList())
    }

    @Test
    fun `deferred maps posted in the earlier object form still decode`() {
        val request = parseDeferredRenderRequest(
            """{"props":{"m":{"${'$'}bml":"map","value":{"a":1}}},"page":"/","path":"/","query":{}}""",
        )
        assertEquals(mapOf("a" to 1), request?.props?.get("m"))
    }

    @Test
    fun `malformed or deeply nested deferred props are rejected as invalid requests`() {
        fun request(props: String) = parseDeferredRenderRequest("""{"props":$props,"page":"/","path":"/","query":{}}""")

        assertEquals(null, request("""{"m":{"${'$'}bml":"map","value":[["a",1],["a",2]]}}"""))
        assertEquals(null, request("""{"m":{"${'$'}bml":"map","value":[["a"]]}}"""))
        assertEquals(null, request("""{"m":{"${'$'}bml":"map","value":[[1,"a"]]}}"""))

        val shallow = "[".repeat(60) + "]".repeat(60)
        assertEquals(1, request("""{"v":$shallow}""")?.props?.size)
        // A tiny body nested far past the limit must fail as a 422, never overflow the stack.
        val deep = "[".repeat(100_000) + "]".repeat(100_000)
        assertEquals(null, request("""{"v":$deep}"""))
    }

    @Test
    fun `deferred render request is strict and preserves typed props`() {
        assertEquals(
            BmlDeferredRenderRequest(
                props = mapOf("id" to 7, "enabled" to true),
                page = "/accounts/{id}",
                path = "/accounts/42",
                query = mapOf("tab" to "activity"),
                locale = "es-419",
            ),
            parseDeferredRenderRequest(
                """{"props":{"id":7,"enabled":true},"page":"/accounts/{id}","path":"/accounts/42","query":{"tab":"activity"},"locale":"es-419"}""",
            ),
        )
    }

    @Test
    fun `deferred prop wire round trip preserves nested JVM types`() {
        val encoded = deferredPropsToJson(
            linkedMapOf(
                "ids" to listOf(9_007_199_254_740_993L, 2L),
                "weights" to mapOf("primary" to 1.5f),
                "ratios" to listOf(1.0, 1.5),
                "metadata" to mapOf("\$bml" to "category", "value" to 1),
                "codes" to charArrayOf('a', 'z'),
                "counts" to intArrayOf(3, 5),
                "samples" to doubleArrayOf(1.0, 2.5),
            ),
        )
        val request = parseDeferredRenderRequest(
            """{"props":$encoded,"page":"/items","path":"/items","query":{}}""",
        ) ?: error("request did not parse")

        val decodedIds = request.props.getValue("ids") as List<*>
        val decodedRatios = request.props.getValue("ratios") as List<*>
        val ids = requiredDeferredProp<List<Long>>(request.props, "ids", "List<Long>")
        val weights = requiredDeferredProp<Map<String, Float>>(request.props, "weights", "Map<String, Float>")
        val ratios = requiredDeferredProp<List<Double>>(request.props, "ratios", "List<Double>")
        val samples = requiredDeferredProp<DoubleArray>(request.props, "samples", "DoubleArray")
        assertEquals(listOf(9_007_199_254_740_993L, 2L), ids)
        assertTrue(decodedIds.all { it is Long })
        assertEquals(1.5f, weights.getValue("primary"))
        assertEquals(listOf(1.0, 1.5), ratios)
        assertTrue(decodedRatios.all { it is Double })
        assertTrue(samples.contentEquals(doubleArrayOf(1.0, 2.5)))
        assertEquals(mapOf("\$bml" to "category", "value" to 1), request.props.getValue("metadata"))
        assertTrue(request.props.getValue("codes") is CharArray)
        assertTrue(request.props.getValue("counts") is IntArray)
        assertTrue((request.props.getValue("codes") as CharArray).contentEquals(charArrayOf('a', 'z')))
        assertTrue((request.props.getValue("counts") as IntArray).contentEquals(intArrayOf(3, 5)))
    }

    @Test
    fun `deferred prop wire types reject aliases and malformed generic shapes`() {
        assertTrue(isSupportedDeferredPropType("Map<String, List<Long?>>"))
        assertTrue(isSupportedDeferredPropType("kotlin.collections.List<out kotlin.String>"))
        assertTrue(isSupportedDeferredPropType("List<*>"))
        assertTrue(!isSupportedDeferredPropType("*"))
        assertTrue(!isSupportedDeferredPropType("out String"))
        assertTrue(!isSupportedDeferredPropType("Map<in String, String>"))
        assertTrue(!isSupportedDeferredPropType("AccountId"))
        assertTrue(!isSupportedDeferredPropType("List<AccountId>"))
        assertTrue(!isSupportedDeferredPropType("String<Int>"))
        assertTrue(!isSupportedDeferredPropType("Map<String>"))
        assertTrue(!isSupportedDeferredPropType("Map<Int, String>"))
        assertTrue(!isSupportedDeferredPropType("Map<CharSequence, String>"))
        assertTrue(!isSupportedDeferredPropType("Collection<String>"))
        assertTrue(!isSupportedDeferredPropType("Iterable<String>"))
        assertTrue(!isSupportedDeferredPropType("example.String"))
        assertTrue(!isSupportedDeferredPropType("example.List<String>"))
        assertTrue(!isSupportedDeferredPropType("example.Map<String, String>"))
    }

    @Test
    fun `deferred render request rejects malformed context`() {
        for (body in listOf(
            """{"page":"/","path":"/","query":{}}""",
            """{"props":[],"page":"/","path":"/","query":{}}""",
            """{"props":{},"page":"https://example.test/","path":"/","query":{}}""",
            """{"props":{},"page":"/","path":"https://example.test/","query":{}}""",
            """{"props":{},"page":"/","path":"//example.test/","query":{}}""",
            """{"props":{},"page":"/","path":"/?x=1","query":{}}""",
            """{"props":{},"page":"/","path":"/","query":{"page":2}}""",
            """{"props":{},"page":"/","path":"/","query":{},"locale":4}""",
        )) {
            assertNull(parseDeferredRenderRequest(body), body)
        }
    }

    @Test
    fun `deferred render request rejects non-finite numbers at every nesting level`() {
        for (props in listOf(
            """{"value":1e999}""",
            """{"values":[1e999]}""",
            """{"values":{"nested":1e999}}""",
            """{"values":{"${'$'}bml":"double-array","value":[1e999]}}""",
        )) {
            assertNull(
                parseDeferredRenderRequest("""{"props":$props,"page":"/","path":"/","query":{}}"""),
                props,
            )
        }

        assertFailsWith<InvalidBmlDeferredPropsException> {
            requiredDeferredProp<List<Double>>(
                mapOf("values" to listOf(Double.POSITIVE_INFINITY)),
                "values",
                "List<Double>",
            )
        }
        assertFailsWith<InvalidBmlDeferredPropsException> {
            requiredDeferredProp<DoubleArray>(
                mapOf("values" to doubleArrayOf(Double.NaN)),
                "values",
                "DoubleArray",
            )
        }
    }

    @Test
    fun `required deferred prop reports missing and incompatible values`() {
        assertEquals("42", requiredDeferredProp<String>(mapOf("id" to "42"), "id"))
        assertEquals(7L, requiredDeferredProp<Long>(mapOf("count" to 7), "count"))
        assertEquals(1.5f, requiredDeferredProp<Float>(mapOf("ratio" to 1.5), "ratio"))
        assertEquals('x', requiredDeferredProp<Char>(mapOf("code" to "x"), "code"))
        assertEquals(9L, deferredPropOrDefault(mapOf("count" to 9), "count") { 3L })
        assertEquals(3L, deferredPropOrDefault(emptyMap(), "count") { 3L })
        assertFailsWith<InvalidBmlDeferredPropsException> { requiredDeferredProp<String>(emptyMap(), "id") }
        assertFailsWith<InvalidBmlDeferredPropsException> {
            requiredDeferredProp<String>(mapOf("id" to 42), "id")
        }
        assertFailsWith<InvalidBmlDeferredPropsException> {
            requiredDeferredProp<Byte>(mapOf("count" to 256), "count")
        }
        assertFailsWith<InvalidBmlDeferredPropsException> {
            requiredDeferredProp<List<Long>>(
                mapOf("ids" to listOf("not-a-long")),
                "ids",
                "List<Long>",
            )
        }
        assertFailsWith<InvalidBmlDeferredPropsException> {
            requiredDeferredProp<Map<String, List<Long>>>(
                mapOf("groups" to mapOf("primary" to listOf(1L, "wrong"))),
                "groups",
                "Map<String, List<Long>>",
            )
        }
    }

    // ── propsFromJson: decoding a client's posted state for a sliver re-render ──

    @Test
    fun `propsFromJson decodes integral numbers to Int so typed casts resolve`() {
        val props = propsFromJson("""{"count": 7}""")
        assertEquals(7, props["count"])
        assertTrue(props["count"] is Int, "integral numbers must decode to Int for `as Int` props")
    }

    @Test
    fun `propsFromJson decodes strings, booleans, and decimals`() {
        val props = propsFromJson("""{"label":"hi","flag":true,"ratio":1.5,"nothing":null}""")
        assertEquals("hi", props["label"])
        assertEquals(true, props["flag"])
        assertEquals(1.5, props["ratio"])
        assertTrue(props.containsKey("nothing") && props["nothing"] == null)
    }

    @Test
    fun `propsFromJson decodes arrays and nested objects`() {
        val props = propsFromJson("""{"items":["a","b"],"nested":{"x":1}}""")
        assertEquals(listOf("a", "b"), props["items"])
        assertEquals(mapOf("x" to 1), props["nested"])
    }

    @Test
    fun `propsFromJson round-trips propsToJson`() {
        val original = linkedMapOf<String, Any?>("count" to 3, "tone" to "hot", "tags" to listOf("x", "y"))
        assertEquals(original, propsFromJson(propsToJson(original)))
    }

    @Test
    fun `propsFromJson returns empty for blank or non-object input`() {
        assertTrue(propsFromJson("").isEmpty())
        assertTrue(propsFromJson("[1,2,3]").isEmpty())
        assertTrue(propsFromJson("not json").isEmpty())
    }
}
