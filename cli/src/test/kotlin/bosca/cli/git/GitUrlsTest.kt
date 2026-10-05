package bosca.cli.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitUrlsTest {

    @Test
    fun `parseRef splits owner and repo`() {
        val ref = GitUrls.parseRef("acme/widgets")
        assertEquals("acme", ref.owner)
        assertEquals("widgets", ref.repo)
    }

    @Test
    fun `parseRef strips a trailing dot-git`() {
        assertEquals(GitUrls.RepoRef("acme", "widgets"), GitUrls.parseRef("acme/widgets.git"))
    }

    @Test
    fun `parseRef tolerates a trailing slash and surrounding whitespace`() {
        assertEquals(GitUrls.RepoRef("acme", "widgets"), GitUrls.parseRef("  acme/widgets/  "))
    }

    @Test
    fun `parseRef rejects a single segment`() {
        assertFailsWith<IllegalArgumentException> { GitUrls.parseRef("widgets") }
    }

    @Test
    fun `parseRef rejects three segments`() {
        assertFailsWith<IllegalArgumentException> { GitUrls.parseRef("a/b/c") }
    }

    @Test
    fun `parseRef rejects a blank owner`() {
        assertFailsWith<IllegalArgumentException> { GitUrls.parseRef("/widgets") }
    }

    @Test
    fun `looksLikeUrl recognises schemes and scp addresses`() {
        assertTrue(GitUrls.looksLikeUrl("https://git.acme.io/acme/widgets.git"))
        assertTrue(GitUrls.looksLikeUrl("git@git.acme.io:acme/widgets.git"))
        assertFalse(GitUrls.looksLikeUrl("acme/widgets"))
    }

    @Test
    fun `host extracts the hostname from an https url`() {
        assertEquals("git.acme.io", GitUrls.host("https://git.acme.io/acme/widgets.git"))
    }

    @Test
    fun `host returns null for non-urls and scp addresses`() {
        assertNull(GitUrls.host("acme/widgets"))
        assertNull(GitUrls.host("git@git.acme.io:acme/widgets.git"))
    }

    @Test
    fun `directoryNameFor mirrors git's default naming`() {
        assertEquals("widgets", GitUrls.directoryNameFor("https://git.acme.io/acme/widgets.git"))
        assertEquals("widgets", GitUrls.directoryNameFor("https://git.acme.io/acme/widgets/"))
    }
}
