package bosca.bml.message.server

import bosca.bml.render.HtmlWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The stateless token + rewrite rules in isolation: HMAC round-trip, tamper/garbage rejection,
 * cross-secret rejection, and the deliverability carve-outs (unsubscribe, mailto:, fragments).
 */
class LinkTrackingTest {

    private val tracking = LinkTracking("unit-secret", "https://email.example.com/")

    @Test
    fun `a click url round-trips its payload through verify`() {
        val url = tracking.clickUrl("https://example.com/a?b=c&d=e", messageId = "m-1", recipientId = "r-1")
        assertTrue(url.startsWith("https://email.example.com/c/"), url)
        val payload = tracking.verify(url.substringAfterLast("/c/"))
        assertEquals("https://example.com/a?b=c&d=e", payload?.u)
        assertEquals("m-1", payload?.m)
        assertEquals("r-1", payload?.r)
    }

    @Test
    fun `the open pixel token carries the ids with an empty url`() {
        val payload = tracking.verify(tracking.openPixelUrl("m-2", null).substringAfterLast("/o/"))
        assertEquals("", payload?.u)
        assertEquals("m-2", payload?.m)
        assertNull(payload?.r)
    }

    @Test
    fun `tampered, truncated, cross-secret, and garbage tokens all verify to null`() {
        val token = tracking.clickUrl("https://example.com", "m-3", null).substringAfterLast("/c/")
        assertNull(tracking.verify(token.dropLast(1)))
        assertNull(tracking.verify("x$token"))
        assertNull(tracking.verify(token.replace('.', '_')))
        assertNull(tracking.verify(""))
        assertNull(tracking.verify("no-dot-here"))
        assertNull(tracking.verify("$token."))
        assertNull(LinkTracking("другой", "https://email.example.com").verify(token))
    }

    @Test
    fun `rewrite tracks content links but spares excluded, mailto, and fragment hrefs`() {
        val html = """<body><a href="https://example.com/go">Go</a>""" +
            """<a href="https://example.com/unsub">Unsubscribe</a>""" +
            """<a href="mailto:hi@example.com">Mail</a><a href="#top">Top</a></body>"""
        val out = tracking.rewrite(html, "m-4", "r-4", excludeUrls = setOf("https://example.com/unsub"))
        assertTrue("""href="https://email.example.com/c/""" in out, out)
        assertTrue("""href="https://example.com/go"""" !in out, out)
        assertTrue("""href="https://example.com/unsub"""" in out, out)
        assertTrue("""href="mailto:hi@example.com"""" in out, out)
        assertTrue("""href="#top"""" in out, out)
    }

    @Test
    fun `rewrite signs the decoded pull request destination`() {
        val destination = "https://studio.example.com/git/pulls/6b69ee7c-2d86-4771-b49e-02c0da7b0ab5" +
            "?repo=ad02c271-f716-4c6d-af50-87ea402a4828&number=3"
        val html = HtmlWriter().markup("<a").attr("href", destination).markup(">View pull request</a>").toString()
        assertTrue("&amp;number=3" in html, html)

        val rewritten = tracking.rewrite(html, "m-pr", "r-pr", emptySet())
        val token = Regex("""/c/([A-Za-z0-9_.-]+)""").find(rewritten)?.groupValues?.get(1)
            ?: error("No click token in: $rewritten")
        val payload = tracking.verify(token)
        assertEquals(destination, payload?.u)
        assertEquals("m-pr", payload?.m)
        assertEquals("r-pr", payload?.r)
    }

    @Test
    fun `rewrite decodes named and numeric entities once and preserves URL encoding`() {
        val html = """<a href="https://example.com/?a=1&#38;b=2&#x26;c=&quot;x&quot;&amp;literal=&amp;amp;&amp;encoded=%26amp%3B">Go</a>"""
        val rewritten = tracking.rewrite(html, "m-entities", null, emptySet())
        val token = Regex("""/c/([A-Za-z0-9_.-]+)""").find(rewritten)?.groupValues?.get(1)
            ?: error("No click token in: $rewritten")
        assertEquals(
            "https://example.com/?a=1&b=2&c=\"x\"&literal=&amp;&encoded=%26amp%3B",
            tracking.verify(token)?.u,
        )
    }

    @Test
    fun `rewrite compares decoded URLs to exclusions and preserves their HTML`() {
        val unsubscribe = "https://example.com/unsub?message=m-1&recipient=r-1"
        val preferences = "https://example.com/preferences?message=m-1&recipient=r-1"
        val html = HtmlWriter().markup("<body><a").attr("href", unsubscribe).markup(">Unsubscribe</a><a")
            .attr("href", preferences).markup(">Preferences</a></body>").toString()
        val rewritten = tracking.rewrite(html, "m-1", "r-1", setOf(unsubscribe, preferences))
        assertEquals(html.substringBefore("</body>"), rewritten.substringBefore("<img"))
        assertTrue("/c/" !in rewritten, rewritten)
    }

    @Test
    fun `the open pixel lands inside the body or appends when there is none`() {
        val withBody = tracking.rewrite("<body><p>Hi</p></body>", "m-5", null, emptySet())
        assertTrue(withBody.indexOf("/o/") < withBody.indexOf("</body>"), withBody)
        val fragment = tracking.rewrite("<p>Hi</p>", "m-5", null, emptySet())
        assertTrue("/o/" in fragment.substringAfter("</p>"), fragment)
    }
}
