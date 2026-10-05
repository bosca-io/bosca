package bosca.bml.render

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BmlRenderTest {

    @Test
    fun `renders a page function to html`() = runTest {
        val html = renderToString { ctx ->
            ctx.writer.markup("<h1>").text("Hi & bye").markup("</h1>")
        }
        assertEquals("<h1>Hi &amp; bye</h1>", html)
    }
}
