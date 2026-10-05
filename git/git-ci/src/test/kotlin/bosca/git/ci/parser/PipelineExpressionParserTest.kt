package bosca.git.ci.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PipelineExpressionParserTest {

    private val parser = PipelineExpressionParser()

    private val context = ExpressionContext(
        ref = "refs/heads/main",
        branch = "main",
        event = "push",
        jobStatus = "success",
        matrix = mapOf("java" to "21", "os" to "linux"),
        env = mapOf("CI" to "true"),
        secrets = mapOf("TOKEN" to "secret123")
    )

    @Test
    fun `evaluate string equality`() {
        assertTrue(parser.evaluateBoolean("ref == 'refs/heads/main'", context))
        assertFalse(parser.evaluateBoolean("ref == 'refs/heads/develop'", context))
    }

    @Test
    fun `evaluate string inequality`() {
        assertTrue(parser.evaluateBoolean("ref != 'refs/heads/develop'", context))
        assertFalse(parser.evaluateBoolean("ref != 'refs/heads/main'", context))
    }

    @Test
    fun `evaluate logical AND`() {
        assertTrue(parser.evaluateBoolean("ref == 'refs/heads/main' && event == 'push'", context))
        assertFalse(parser.evaluateBoolean("ref == 'refs/heads/main' && event == 'tag'", context))
    }

    @Test
    fun `evaluate logical OR`() {
        assertTrue(parser.evaluateBoolean("event == 'push' || event == 'tag'", context))
        assertFalse(parser.evaluateBoolean("event == 'manual' || event == 'tag'", context))
    }

    @Test
    fun `evaluate logical NOT`() {
        assertTrue(parser.evaluateBoolean("!false", context))
        assertFalse(parser.evaluateBoolean("!true", context))
        assertTrue(parser.evaluateBoolean("!(ref == 'refs/heads/develop')", context))
    }

    @Test
    fun `evaluate matrix variables`() {
        assertTrue(parser.evaluateBoolean("matrix.java == '21'", context))
        assertTrue(parser.evaluateBoolean("matrix.os == 'linux'", context))
    }

    @Test
    fun `evaluate startsWith function`() {
        assertTrue(parser.evaluateBoolean("startsWith(ref, 'refs/heads/')", context))
        assertFalse(parser.evaluateBoolean("startsWith(ref, 'refs/tags/')", context))
    }

    @Test
    fun `evaluate endsWith function`() {
        assertTrue(parser.evaluateBoolean("endsWith(ref, '/main')", context))
        assertFalse(parser.evaluateBoolean("endsWith(ref, '/develop')", context))
    }

    @Test
    fun `evaluate contains function`() {
        assertTrue(parser.evaluateBoolean("contains(ref, 'heads')", context))
        assertFalse(parser.evaluateBoolean("contains(ref, 'tags')", context))
    }

    @Test
    fun `containsToken matches complete comma separated values only`() {
        val ctx = context.copy(extra = mapOf("release.projects" to "mobile-web,web,server"))
        assertTrue(parser.evaluateBoolean("containsToken(release.projects, 'web')", ctx))
        assertFalse(parser.evaluateBoolean("containsToken(release.projects, 'mobile')", ctx))
        assertFalse(parser.evaluateBoolean("containsToken(release.projects, 'serve')", ctx))
    }

    @Test
    fun `evaluate status functions`() {
        assertTrue(parser.evaluateBoolean("success()", context))
        assertFalse(parser.evaluateBoolean("failure()", context))
        assertFalse(parser.evaluateBoolean("cancelled()", context))
        assertTrue(parser.evaluateBoolean("always()", context))

        val failContext = context.copy(jobStatus = "failure")
        assertTrue(parser.evaluateBoolean("failure()", failContext))
        assertFalse(parser.evaluateBoolean("success()", failContext))
    }

    @Test
    fun `evaluate numeric comparisons`() {
        val ctx = context.copy(extra = mapOf("count" to "5"))
        assertTrue(parser.evaluateBoolean("count > 3", ctx))
        assertTrue(parser.evaluateBoolean("count >= 5", ctx))
        assertTrue(parser.evaluateBoolean("count <= 5", ctx))
        assertFalse(parser.evaluateBoolean("count < 5", ctx))
    }

    @Test
    fun `interpolate template strings`() {
        assertEquals("refs/heads/main", parser.interpolate("\${{ ref }}", context))
        assertEquals("build-main", parser.interpolate("build-\${{ branch }}", context))
        assertEquals("java-21-linux", parser.interpolate("java-\${{ matrix.java }}-\${{ matrix.os }}", context))
    }

    @Test
    fun `evaluate boolean literals`() {
        assertTrue(parser.evaluateBoolean("true", context))
        assertFalse(parser.evaluateBoolean("false", context))
    }

    @Test
    fun `evaluate parenthesized expressions`() {
        assertTrue(parser.evaluateBoolean("(true && true) || false", context))
        assertFalse(parser.evaluateBoolean("true && (false || false)", context))
    }

    @Test
    fun `evaluate complex expression`() {
        assertTrue(parser.evaluateBoolean(
            "ref == 'refs/heads/main' && event == 'push' && !failure()",
            context
        ))
    }

    @Test
    fun `resolve unknown variable returns null`() {
        assertFalse(parser.evaluateBoolean("nonexistent == 'value'", context))
    }

    @Test
    fun `tag and version resolve from a tag ref — the artifact-coordinate tokens`() {
        val tagContext = ExpressionContext(ref = "refs/tags/v1.4.0", event = "tag")
        assertEquals("my-api:1.4.0", parser.interpolate("my-api:\${{ version }}", tagContext))
        assertEquals("v1.4.0", parser.interpolate("\${{ tag }}", tagContext))
        // A tag without the leading v passes through version unchanged.
        assertEquals("2024.1", parser.interpolate("\${{ version }}", ExpressionContext(ref = "refs/tags/2024.1")))
    }

    @Test
    fun `tag and version are empty on a branch ref`() {
        assertEquals("", parser.interpolate("\${{ tag }}", context))
        assertEquals("", parser.interpolate("\${{ version }}", context))
    }
}
