package bosca.git.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepositoryMarkdownTest {
    @Test
    fun `relative reference and root assets remain pinned to the document commit`() {
        val source =
            """
            # Install
            ![Relative image][banner]

            [banner]: ../images/a%20b%23c.png "Banner"

            ![Root image](/assets/logo.png)
            [Next](./next.md#install)
            ![External](https://assets.example.test/logo.png)
            """
                .trimIndent()
        val html =
            repositoryMarkdown(
                source,
                "repo",
                "a".repeat(40),
                "docs/guide.md",
                "https://git.example.test/alice/project.git",
                "/repositories?state=archived&owner=alice",
            )
        assertTrue("/alice/project/raw/${"a".repeat(40)}/images/a%20b%23c.png" in html, html)
        assertTrue("/assets/logo.png" in html && "alt=\"Root image\"" in html, html)
        assertTrue("file=docs%2Fnext.md" in html && "#install" in html, html)
        assertTrue("https://assets.example.test/logo.png" in html, html)
        assertTrue("id=\"install\"" in html, html)
    }

    @Test
    fun `untrusted HTML schemes and traversal cannot execute or escape the repository`() {
        val html =
            repositoryMarkdown(
                "<script>alert(1)</script>\n\n[Bad](javascript:alert(1))\n\n![Escape](../../secret.png)\n\n# Repeat\n# Repeat",
                "repo",
                "abc",
                "docs/a.md",
                "https://git.example.test/a/r.git",
                "/repositories",
            )
        assertFalse("<script>" in html, html)
        assertFalse("href=\"javascript:" in html, html)
        assertFalse("raw/abc/../" in html, html)
        assertTrue("id=\"repeat\"" in html && "id=\"repeat-1\"" in html, html)
        assertEquals(
            "",
            markdownDestination(
                "../../secret.png",
                true,
                "repo",
                "abc",
                "docs/a.md",
                "https://git.example.test/a/r.git",
                "/repositories",
            ),
        )
    }

    @Test
    fun `unsafe list redirects and file paths are rejected`() {
        assertEquals("/repositories", safeListHref("//evil.example/repositories"))
        assertEquals("/repositories", safeListHref("/repositories/../../login"))
        kotlin.test.assertFailsWith<IllegalArgumentException> { repositoryFilePath("../escape") }
        kotlin.test.assertFailsWith<IllegalArgumentException> { repositoryFilePath("a//b") }
        assertEquals("docs/guide.md", repositoryFilePath("docs/guide.md"))
    }
}
