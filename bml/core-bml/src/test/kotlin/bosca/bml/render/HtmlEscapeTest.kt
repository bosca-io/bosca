package bosca.bml.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Edge cases for [Html] escaping and [HtmlWriter] null/type handling (security-relevant). */
class HtmlEscapeTest {

    @Test
    fun `escape returns the same instance when there is nothing to escape (fast path)`() {
        val s = "nothing special here 123"
        assertSame(s, Html.escape(s))
    }

    @Test
    fun `escape encodes all five sensitive characters`() {
        assertEquals("&amp;&lt;&gt;&quot;&#39;", Html.escape("&<>\"'"))
    }

    @Test
    fun `escapeAttribute matches escape`() {
        assertEquals(Html.escape("""a<b>"c"&'d"""), Html.escapeAttribute("""a<b>"c"&'d"""))
    }

    @Test
    fun `text escapes and tolerates null`() {
        assertEquals("&lt;b&gt;", HtmlWriter().text("<b>").toString())
        assertEquals("", HtmlWriter().text(null).toString())
        assertEquals("A&amp;&lt;&gt;&quot;&#39; café", HtmlWriter().text("A&<>\"' café").toString())
    }

    @Test
    fun `raw is verbatim and tolerates null`() {
        assertEquals("<b>", HtmlWriter().raw("<b>").toString())
        assertEquals("", HtmlWriter().raw(null).toString())
    }

    @Test
    fun `attr escapes non-string values via toString`() {
        assertEquals(""" data-n="3"""", HtmlWriter().attr("data-n", 3).toString())
        assertEquals(""" t="a&amp;b"""", HtmlWriter().attr("t", "a&b").toString())
        assertEquals(
            """ title="A&amp;&lt;&gt;&quot;&#39; café"""",
            HtmlWriter().attr("title", "A&<>\"' café").toString(),
        )
    }
}
