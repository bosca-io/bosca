package bosca.bml.server

import bosca.bml.project.CompiledProject
import bosca.bml.render.BmlPageRenderer
import bosca.bml.render.RenderContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BmlPageRendererTest {

    @Test
    fun `renders using route params + token threaded through RenderContext`() = runTest {
        val page = object : BmlPageRenderer {
            override val route = "/lists/{id}"
            override suspend fun render(ctx: RenderContext) {
                ctx.writer.markup("<h1>${ctx.params["id"]}</h1><p>${ctx.token}</p>")
            }
        }

        assertEquals("/lists/{id}", page.route)
        assertEquals("text/html", page.contentType)
        assertNull(page.clientModule, "a page without a client bundle defaults clientModule to null")

        val ctx = RenderContext(token = "Bearer abc", params = mapOf("id" to "42"))
        page.render(ctx)
        assertEquals("<h1>42</h1><p>Bearer abc</p>", ctx.toString())
    }

    @Test
    fun `server rejects an invalid page content type when building the generation`() {
        val page = object : BmlPageRenderer {
            override val route = "/invalid"
            override val contentType = "not-a-media-type"
            override suspend fun render(ctx: RenderContext) = Unit
        }

        assertFailsWith<IllegalArgumentException> {
            BmlServer(CompiledProject(name = "test", version = "1"), listOf(page))
        }
    }
}
