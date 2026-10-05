package bosca.kubernetes.pipelines

import bosca.workops.deploy.ValuesVersionRewriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [ValuesVersionRewriter]: Flux-style, line-surgical version pinning in Helm values files. */
class ValuesVersionRewriterTest {

    private val values = """
        # my-api deployment values
        replicaCount: 2

        image:
          repository: artifacts.example.io/docker/my-api
          tag: "1.3.0"  # pinned by the release pipeline
          pullPolicy: IfNotPresent

        sidecar:
          image:
            tag: 0.9.1

        resources: {}
    """.trimIndent()

    @Test
    fun `sets the nested path's scalar and preserves everything else byte-for-byte`() {
        val out = ValuesVersionRewriter.rewrite(values, listOf("image.tag"), "1.4.0")
        assertTrue("""tag: "1.4.0"  # pinned by the release pipeline""" in out, out)
        // Only that one line changed.
        val diff = values.lines().zip(out.lines()).filter { (a, b) -> a != b }
        assertEquals(1, diff.size, out)
        // The deeper same-named key is untouched.
        assertTrue("tag: 0.9.1" in out, out)
    }

    @Test
    fun `rewrites multiple paths in one pass`() {
        val out = ValuesVersionRewriter.rewrite(values, listOf("image.tag", "sidecar.image.tag"), "2.0.0")
        assertTrue("""tag: "2.0.0"  # pinned by the release pipeline""" in out, out)
        assertTrue(out.lines().any { it == """    tag: "2.0.0"""" }, out)
    }

    @Test
    fun `a top-level path works`() {
        val out = ValuesVersionRewriter.rewrite("appVersion: old\nname: x", listOf("appVersion"), "3.1.4")
        assertEquals("appVersion: \"3.1.4\"\nname: x", out)
    }

    @Test
    fun `a missing path fails loudly rather than deploying without the pin`() {
        val e = assertFailsWith<IllegalStateException> {
            ValuesVersionRewriter.rewrite(values, listOf("image.digest"), "1.4.0")
        }
        assertTrue("image.digest" in (e.message ?: ""), e.message)
    }

    @Test
    fun `a same-named key at the wrong depth does not match`() {
        // "tag" exists only under image/sidecar.image — a bare top-level path must not match either.
        assertFailsWith<IllegalStateException> { ValuesVersionRewriter.rewrite(values, listOf("tag"), "1.4.0") }
    }

    @Test
    fun `comment lines and list items never confuse the path tracking`() {
        val tricky = """
            # image:
            #   tag: not-real
            images:
              - name: a
                tag: list-item
            image:
              tag: 1.0.0
        """.trimIndent()
        val out = ValuesVersionRewriter.rewrite(tricky, listOf("image.tag"), "9.9.9")
        assertTrue("""  tag: "9.9.9"""" in out, out)
        assertTrue("tag: list-item" in out, out)
        assertTrue("#   tag: not-real" in out, out)
    }

    @Test
    fun `a hash inside a quoted value is not a comment`() {
        val content = "image:\n  tag: \"build#42\" # keep"
        val out = ValuesVersionRewriter.rewrite(content, listOf("image.tag"), "1.0.0")
        assertEquals("image:\n  tag: \"1.0.0\"  # keep", out)
    }
}
