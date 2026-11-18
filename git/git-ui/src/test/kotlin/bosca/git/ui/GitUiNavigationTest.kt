package bosca.git.ui

import bosca.bml.render.RenderContext
import bosca.bml.render.BmlBundledPage
import bosca.design.model.SubsystemNavigationItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertSame

class GitUiNavigationTest {
    private fun navigation(product: String) = GitUiNavigation(
        listOf(SubsystemNavigationItem("catalog", product, "Browse the catalog", "folder", "#64748b", "/catalog", "Tools")),
        product,
    )

    @Test
    fun `standalone header has only Git navigation`() = runBlocking {
        val ctx = RenderContext()
        requireNotNull(GitUi.componentRenderers["git-header"]).render(ctx, emptyMap()) {}
        val html = ctx.writer.toString()
        assertTrue("Browse repositories, review pull requests, and search code" in html)
        assertFalse("<dialog" in html)
    }

    @Test
    fun `importing hosts keep independent navigation and render revisions`() = runBlocking {
        val hosts = listOf(navigation("First"), navigation("Second"))
        val headers = coroutineScope {
            hosts.map { host -> async {
                withContext(host) {
                    yield()
                    assertEquals(host, currentGitUiNavigation())
                    val ctx = RenderContext()
                    requireNotNull(GitUi.componentRenderers(host)["git-header"]).render(ctx, emptyMap()) {}
                    ctx.writer.toString()
                }
            } }.awaitAll()
        }
        assertTrue("Bosca First subsystems" in headers[0])
        assertFalse("Bosca Second subsystems" in headers[0])
        assertTrue("Bosca Second subsystems" in headers[1])
        assertEquals(GitUiNavigation(), currentGitUiNavigation())
        assertNotEquals(GitUi.pages(hosts[0]).first().renderRevision, GitUi.pages(hosts[1]).first().renderRevision)
        assertSame((GitUi.pages.first() as BmlBundledPage).assets,
            (GitUi.pages(hosts[0]).first() as BmlBundledPage).assets)
    }
}
