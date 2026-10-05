package bosca.bml.render

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Covers the per-type branches of [propsToJson]'s value encoder and the control-char string escaping. */
class PropsEncodeBranchesTest {

    @Serializable
    private data class StoredCounter(val count: Int)

    @Serializable
    private data class StoredText(val value: String)

    @Test
    fun `floats encode, non-finite floats become null`() {
        assertEquals("""{"f":1.5}""", propsToJson(mapOf("f" to 1.5f)))
        assertEquals("""{"f":null}""", propsToJson(mapOf("f" to Float.NaN)))
        assertEquals("""{"f":null}""", propsToJson(mapOf("f" to Float.POSITIVE_INFINITY)))
    }

    @Test
    fun `short and byte encode as numbers`() {
        assertEquals("""{"s":7,"b":3}""", propsToJson(mapOf("s" to 7.toShort(), "b" to 3.toByte())))
    }

    @Test
    fun `char encodes as a string`() {
        assertEquals("""{"c":"x"}""", propsToJson(mapOf("c" to 'x')))
    }

    @Test
    fun `arbitrary Number subtypes fall through to toString`() {
        assertEquals("""{"n":123456789012345678901234567890}""", propsToJson(mapOf("n" to BigInteger("123456789012345678901234567890"))))
    }

    @Test
    fun `primitive arrays encode as json arrays`() {
        assertEquals("""{"a":[1,2,3]}""", propsToJson(mapOf("a" to intArrayOf(1, 2, 3))))
        assertEquals("""{"a":[1,2]}""", propsToJson(mapOf("a" to longArrayOf(1L, 2L))))
        assertEquals("""{"a":[1.5,2.5]}""", propsToJson(mapOf("a" to doubleArrayOf(1.5, 2.5))))
        assertEquals("""{"a":[true,false]}""", propsToJson(mapOf("a" to booleanArrayOf(true, false))))
    }

    @Test
    fun `object arrays encode as json arrays`() {
        assertEquals("""{"a":["x","y"]}""", propsToJson(mapOf("a" to arrayOf("x", "y"))))
    }

    @Test
    fun `tab newline and carriage-return are json-escaped`() {
        assertEquals("""{"k":"a\nb\tc\r"}""", propsToJson(mapOf("k" to "a\nb\tc\r")))
    }

    @Test
    fun `sub-space control chars become unicode escapes`() {
        // input is a real U+0001; expected JSON carries the literal  escape (built via concat to
        // avoid raw-string \u processing ambiguity).
        val expected = "{\"k\":\"" + "\\u0001" + "\"}"
        assertEquals(expected, propsToJson(mapOf("k" to 1.toChar().toString())))
    }

    @Test
    fun `empty string encodes as empty`() {
        assertEquals("""{"k":""}""", propsToJson(mapOf("k" to "")))
    }

    @Test
    fun `parseActionRequest defaults everything on a non-object body`() {
        val req = parseActionRequest("not json")
        assertEquals("", req.page)
        assertEquals("", req.stateKey)
        assertEquals("", req.method)
        assertEquals("", req.state)
    }

    @Test
    fun `encodeActionResponse escapes embedded quotes in html`() {
        val out = encodeActionResponse(IslandActionResult(null, """<a href="x">"""))
        assertEquals("""{"state":null,"html":"<a href=\"x\">"}""", out)
    }

    @Test
    fun `headless action response encodes null html`() {
        assertEquals("""{"state":"{}","html":null}""", encodeActionResponse(IslandActionResult("{}", null)))
    }

    @Test
    fun `action request carries page context and whether a view is mounted`() {
        val req = parseActionRequest(
            """{"page":"/read/{id}","path":"/read/one","query":{"view":"full"},"locale":"fr","stateKey":"access","method":"sync","state":"{}","renderView":false}""",
        )
        assertEquals("/read/one", req.path)
        assertEquals(mapOf("view" to "full"), req.query)
        assertEquals("fr", req.locale)
        assertEquals(false, req.renderView)
    }

    @Test
    fun `decodeClientState distinguishes incompatible models while accepting additive old fields`() {
        assertEquals(3, decodeClientState<StoredCounter>("""{"count":3,"legacy":true}""").count)

        val error = assertFailsWith<InvalidBmlClientStateException> {
            decodeClientState<StoredCounter>("""{"count":"old"}""")
        }
        assertTrue(error.cause is SerializationException)
    }

    @Test
    fun `client state cannot terminate its json script element`() {
        val encoded = encodeClientState(StoredText("</script><script>alert(1)</script>"))
        assertEquals(
            "{\"value\":\"\\u003c/script>\\u003cscript>alert(1)\\u003c/script>\"}",
            encoded,
        )
        assertEquals("</script><script>alert(1)</script>", decodeClientState<StoredText>(encoded).value)
    }
}
