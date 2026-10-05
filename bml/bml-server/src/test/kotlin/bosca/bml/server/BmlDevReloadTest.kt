package bosca.bml.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BmlDevReloadTest {

    @Test
    fun `development mode accepts a case-insensitive Gradle system property`() = withBmlDevProperty("TrUe") {
        assertTrue(bmlDevelopmentMode())
    }

    @Test
    fun `an explicit false Gradle system property keeps production mode off`() = withBmlDevProperty("false") {
        assertFalse(bmlDevelopmentMode())
    }

    @Test
    fun `client script subscribes to the reload endpoint and reloads on a changed generation token`() {
        val script = BmlDevReload.clientScript()
        assertTrue(script.contains("""new EventSource("${BmlDevReload.ENDPOINT}")"""), "connects to the SSE endpoint")
        assertTrue(script.contains("location.reload()"), "reloads when the generation token differs")
        assertTrue(script.contains("booted"), "remembers the first generation token")
    }

    @Test
    fun `client script releases the stream while the tab is hidden and reconnects on focus`() {
        val script = BmlDevReload.clientScript()
        // Hidden tabs must NOT pin one of the browser's ~6 per-origin sockets — that starves
        // the visible tab's navigations when several site tabs are open.
        assertTrue(script.contains("visibilitychange"), "reacts to tab visibility")
        assertTrue(script.contains("es.close()"), "closes the stream when hidden")
        assertTrue(script.contains("pagehide"), "releases on navigation/bfcache entry")
        assertTrue(script.contains("pageshow"), "reconnects on bfcache restore")
        // Reconnect must not double-subscribe: connect() bails when a stream is already open.
        assertTrue(script.contains("if (es) return"), "connect is idempotent")
    }

    @Test
    fun `inject places the script just before the closing body tag`() {
        val html = "<html><body><h1>hi</h1></body></html>"
        val out = BmlDevReload.inject(html, "<script>X</script>")
        assertEquals("<html><body><h1>hi</h1><script>X</script>\n</body></html>", out)
    }

    @Test
    fun `inject appends the script when there is no body tag`() {
        val out = BmlDevReload.inject("<h1>plain</h1>", "<script>X</script>")
        assertEquals("<h1>plain</h1>\n<script>X</script>", out)
    }

    @Test
    fun `inject targets the last body tag in nested or malformed markup`() {
        val html = "<body>a</body><body>b</body>"
        val out = BmlDevReload.inject(html, "<s/>")
        assertEquals("<body>a</body><body>b<s/>\n</body>", out)
    }

    private inline fun withBmlDevProperty(value: String, block: () -> Unit) {
        val previous = System.getProperty("bml.dev")
        try {
            System.setProperty("bml.dev", value)
            block()
        } finally {
            if (previous == null) System.clearProperty("bml.dev") else System.setProperty("bml.dev", previous)
        }
    }
}
