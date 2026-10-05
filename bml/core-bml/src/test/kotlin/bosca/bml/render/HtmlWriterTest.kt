package bosca.bml.render

import kotlin.test.Test
import kotlin.test.assertEquals

class HtmlWriterTest {

    @Test
    fun `escapes text content`() {
        assertEquals("a &amp; b &lt;x&gt; &quot;q&quot; &#39;s&#39;", Html.escape("a & b <x> \"q\" 's'"))
        assertEquals("plain", Html.escape("plain"))
    }

    @Test
    fun `text is escaped, raw is not`() {
        assertEquals("&lt;b&gt;", HtmlWriter().text("<b>").toString())
        assertEquals("<b>", HtmlWriter().raw("<b>").toString())
        assertEquals("", HtmlWriter().text(null).toString())
    }

    @Test
    fun `attr omits null and false, bares true, escapes values`() {
        assertEquals("", HtmlWriter().attr("x", null).toString())
        assertEquals("", HtmlWriter().attr("x", false).toString())
        assertEquals(" disabled", HtmlWriter().attr("disabled", true).toString())
        assertEquals(" href=\"a&amp;b\"", HtmlWriter().attr("href", "a&b").toString())
    }

    @Test
    fun `spread renders each entry`() {
        val out = HtmlWriter().spread(linkedMapOf("a" to "1", "b" to true, "c" to null)).toString()
        assertEquals(" a=\"1\" b", out)
    }

    @Test
    fun `markup is verbatim and writer composes`() {
        val w = HtmlWriter()
        w.markup("<h1>").text("A & B").markup("</h1>")
        assertEquals("<h1>A &amp; B</h1>", w.toString())
    }
}
