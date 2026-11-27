package bosca.design

import bosca.bml.library.BmlLibraryArtifacts
import bosca.bml.render.RenderContext
import bosca.design.generated.BmlComponents
import bosca.design.model.BoardMoveTarget
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class BoardComponentTest {
    @Test fun `board card projects application content and safely encodes neutral destinations`() = runBlocking {
        val tag = BmlLibraryArtifacts.componentId("io.bosca:bml-design", "board-card")
        val context = RenderContext()
        requireNotNull(BmlComponents.renderers[tag]).render(context, mapOf(
            "cardId" to "card", "label" to "Review <proposal>", "movable" to true,
            "targets" to listOf(BoardMoveTarget("done", "Done <safe>")), "preserveKey" to "review-card",
        )) { context.writer.raw("<p>Application content</p>") }
        val html = context.writer.toString()
        assertContains(html, "data-board-card=\"card\"")
        assertContains(html, "data-board-move-handle")
        assertContains(html, "data-bml-preserve=\"review-card\"")
        assertContains(html, "Review &lt;proposal&gt;")
        assertContains(html, "Done &lt;safe&gt;")
        assertContains(html, "Application content")
        assertFalse(html.contains("role=\"group\""))
        assertFalse(html.contains("<proposal>")); assertFalse(html.contains("<safe>"))
    }

    @Test fun `board column owns its scoped layout and names a group within the board`() = runBlocking {
        val tag = BmlLibraryArtifacts.componentId("io.bosca:bml-design", "board-column")
        val context = RenderContext()
        requireNotNull(BmlComponents.renderers[tag]).render(context, mapOf(
            "columnId" to "todo", "label" to "To do", "scrollKey" to "review-todo",
        )) { context.writer.raw("<p>Projected card</p>") }
        val html = context.writer.toString()
        assertContains(html, "data-board-column=\"todo\"")
        assertContains(html, "role=\"group\"")
        assertContains(html, "aria-label=\"To do\"")
        assertContains(html, "data-bml-scroll=\"board-column-review-todo\"")
        assertContains(html, "data-bml-c=\"$tag\"")
        assertFalse(html.contains("ui-section"))
    }
}
