package bosca.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContentTypeTest {

    @Test
    fun `parse simple content type`() {
        val ct = ContentType.parse("application/json")
        assertEquals("application", ct.contentType)
        assertEquals("json", ct.contentSubtype)
        assertTrue(ct.parameters.isEmpty())
    }

    @Test
    fun `parse content type with charset parameter`() {
        val ct = ContentType.parse("text/html; charset=utf-8")
        assertEquals("text", ct.contentType)
        assertEquals("html", ct.contentSubtype)
        assertEquals("utf-8", ct.parameters["charset"])
    }

    @Test
    fun `parse content type with multiple parameters`() {
        val ct = ContentType.parse("text/plain; charset=utf-8; boundary=something")
        assertEquals("text", ct.contentType)
        assertEquals("plain", ct.contentSubtype)
        assertEquals("utf-8", ct.parameters["charset"])
        assertEquals("something", ct.parameters["boundary"])
    }

    @Test
    fun `parse wildcard content type`() {
        val ct = ContentType.parse("*/*")
        assertEquals("*", ct.contentType)
        assertEquals("*", ct.contentSubtype)
    }

    @Test
    fun `parse content type with wildcard subtype`() {
        val ct = ContentType.parse("text/*")
        assertEquals("text", ct.contentType)
        assertEquals("*", ct.contentSubtype)
    }

    @Test
    fun `toString without parameters`() {
        val ct = ContentType("application", "json")
        assertEquals("application/json", ct.toString())
    }

    @Test
    fun `toString with parameters`() {
        val ct = ContentType("text", "html", mapOf("charset" to "utf-8"))
        assertEquals("text/html; charset=utf-8", ct.toString())
    }

    @Test
    fun `parameters without values and token separators are parsed and quoted`() {
        val parsed = ContentType.parse("text/plain; flag")
        assertEquals("", parsed.parameters["flag"])
        assertEquals("text/plain; flag=\"\"", parsed.toString())

        val quoted = ContentType("text", "plain", mapOf("name" to "a b", "escaped" to "a\"b\\c"))
        assertTrue(quoted.toString().contains("name=\"a b\""))
        assertTrue(quoted.toString().contains("escaped=\"a\\\"b\\\\c\""))
    }

    @Test
    fun `parse rejects blank and malformed media types`() {
        assertFailsWith<IllegalArgumentException> { ContentType.parse(" ") }
        assertFailsWith<IllegalArgumentException> { ContentType.parse("plain") }
    }

    @Test
    fun `toString round-trip without parameters`() {
        val original = "application/json"
        val ct = ContentType.parse(original)
        assertEquals(original, ct.toString())
    }

    @Test
    fun `toString round-trip with parameters`() {
        val ct = ContentType.parse("text/html; charset=utf-8")
        val result = ct.toString()
        assertTrue(result.contains("text/html"))
        assertTrue(result.contains("charset=utf-8"))
    }

    @Test
    fun `match exact type`() {
        val ct = ContentType("application", "json")
        assertTrue(ct.match("application/json"))
    }

    @Test
    fun `match with wildcard subtype`() {
        val ct = ContentType("application", "json")
        assertTrue(ct.match("application/*"))
    }

    @Test
    fun `match with wildcard type and subtype`() {
        val ct = ContentType("application", "json")
        assertTrue(ct.match("*/*"))
    }

    @Test
    fun `match fails for different type`() {
        val ct = ContentType("application", "json")
        assertFalse(ct.match("text/json"))
    }

    @Test
    fun `match fails for different subtype`() {
        val ct = ContentType("application", "json")
        assertFalse(ct.match("application/xml"))
    }

    @Test
    fun `match with wildcard type specific subtype`() {
        val ct = ContentType("text", "html")
        assertTrue(ct.match("*/html"))
    }

    @Test
    fun `withoutParameters removes parameters`() {
        val ct = ContentType("text", "html", mapOf("charset" to "utf-8"))
        val stripped = ct.withoutParameters()
        assertEquals("text", stripped.contentType)
        assertEquals("html", stripped.contentSubtype)
        assertTrue(stripped.parameters.isEmpty())
    }

    @Test
    fun `withoutParameters on type without parameters returns equivalent`() {
        val ct = ContentType("application", "json")
        val stripped = ct.withoutParameters()
        assertEquals(ct, stripped)
    }

    @Test
    fun `Application Json constant`() {
        assertEquals("application", ContentType.Application.Json.contentType)
        assertEquals("json", ContentType.Application.Json.contentSubtype)
    }

    @Test
    fun `Application OctetStream constant`() {
        assertEquals("application", ContentType.Application.OctetStream.contentType)
        assertEquals("octet-stream", ContentType.Application.OctetStream.contentSubtype)
    }

    @Test
    fun `Application FormUrlEncoded constant`() {
        assertEquals("application", ContentType.Application.FormUrlEncoded.contentType)
        assertEquals("x-www-form-urlencoded", ContentType.Application.FormUrlEncoded.contentSubtype)
    }

    @Test
    fun `Application Xml constant`() {
        assertEquals("application", ContentType.Application.Xml.contentType)
        assertEquals("xml", ContentType.Application.Xml.contentSubtype)
    }

    @Test
    fun `Application ProtoBuf constant`() {
        assertEquals("application", ContentType.Application.ProtoBuf.contentType)
        assertEquals("protobuf", ContentType.Application.ProtoBuf.contentSubtype)
    }

    @Test
    fun `Application Any constant`() {
        assertEquals("application", ContentType.Application.Any.contentType)
        assertEquals("*", ContentType.Application.Any.contentSubtype)
    }

    @Test
    fun `Text Plain constant`() {
        assertEquals("text", ContentType.Text.Plain.contentType)
        assertEquals("plain", ContentType.Text.Plain.contentSubtype)
    }

    @Test
    fun `Text Html constant`() {
        assertEquals("text", ContentType.Text.Html.contentType)
        assertEquals("html", ContentType.Text.Html.contentSubtype)
    }

    @Test
    fun `Text Css constant`() {
        assertEquals("text", ContentType.Text.Css.contentType)
        assertEquals("css", ContentType.Text.Css.contentSubtype)
    }

    @Test
    fun `Text JavaScript constant`() {
        assertEquals("text", ContentType.Text.JavaScript.contentType)
        assertEquals("javascript", ContentType.Text.JavaScript.contentSubtype)
    }

    @Test
    fun `Text EventStream constant`() {
        assertEquals("text", ContentType.Text.EventStream.contentType)
        assertEquals("event-stream", ContentType.Text.EventStream.contentSubtype)
    }

    @Test
    fun `Text Any constant`() {
        assertEquals("text", ContentType.Text.Any.contentType)
        assertEquals("*", ContentType.Text.Any.contentSubtype)
    }

    @Test
    fun `MultiPart FormData constant`() {
        assertEquals("multipart", ContentType.MultiPart.FormData.contentType)
        assertEquals("form-data", ContentType.MultiPart.FormData.contentSubtype)
    }

    @Test
    fun `Image constants`() {
        assertEquals("image", ContentType.Image.Png.contentType)
        assertEquals("png", ContentType.Image.Png.contentSubtype)
        assertEquals("jpeg", ContentType.Image.Jpeg.contentSubtype)
        assertEquals("gif", ContentType.Image.Gif.contentSubtype)
        assertEquals("*", ContentType.Image.Any.contentSubtype)
    }

    @Test
    fun `data class equality`() {
        val a = ContentType("text", "html")
        val b = ContentType("text", "html")
        assertEquals(a, b)
    }

    @Test
    fun `data class equality with parameters`() {
        val a = ContentType("text", "html", mapOf("charset" to "utf-8"))
        val b = ContentType("text", "html", mapOf("charset" to "utf-8"))
        assertEquals(a, b)
    }
}
