package bosca.cli.ci

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExpressionParserTest {

    private val parser = ExpressionParser()
    private val defaultContext = ExpressionContext(
        ref = "refs/heads/main",
        branch = "main",
        event = "push",
        jobStatus = "success",
    )

    @Test
    fun `tag and version resolve from a tag ref and are empty otherwise`() {
        val parser = ExpressionParser()
        val tagged = ExpressionContext(ref = "refs/tags/v6.0.7")
        assertEquals("v6.0.7", parser.interpolate("${'$'}{{ tag }}", tagged))
        assertEquals("6.0.7", parser.interpolate("${'$'}{{ version }}", tagged))
        // A tag without the v prefix passes through version unchanged.
        assertEquals("6.0.7", parser.interpolate("${'$'}{{ version }}", ExpressionContext(ref = "refs/tags/6.0.7")))
        // Branch refs have no tag.
        assertEquals("", parser.interpolate("${'$'}{{ tag }}", ExpressionContext(ref = "refs/heads/main")))
    }

    @Test
    fun `evaluateBoolean returns true for literal true`() {
        assertTrue(parser.evaluateBoolean("true", defaultContext))
    }

    @Test
    fun `evaluateBoolean returns false for literal false`() {
        assertFalse(parser.evaluateBoolean("false", defaultContext))
    }

    @Test
    fun `success function returns true when jobStatus is success`() {
        assertTrue(parser.evaluateBoolean("success()", defaultContext))
    }

    @Test
    fun `success function returns false when jobStatus is failure`() {
        val ctx = defaultContext.copy(jobStatus = "failure")
        assertFalse(parser.evaluateBoolean("success()", ctx))
    }

    @Test
    fun `failure function returns true when jobStatus is failure`() {
        val ctx = defaultContext.copy(jobStatus = "failure")
        assertTrue(parser.evaluateBoolean("failure()", ctx))
    }

    @Test
    fun `failure function returns false when jobStatus is success`() {
        assertFalse(parser.evaluateBoolean("failure()", defaultContext))
    }

    @Test
    fun `cancelled function returns true when jobStatus is cancelled`() {
        val ctx = defaultContext.copy(jobStatus = "cancelled")
        assertTrue(parser.evaluateBoolean("cancelled()", ctx))
    }

    @Test
    fun `always function returns true regardless of status`() {
        assertTrue(parser.evaluateBoolean("always()", defaultContext))
        assertTrue(parser.evaluateBoolean("always()", defaultContext.copy(jobStatus = "failure")))
        assertTrue(parser.evaluateBoolean("always()", defaultContext.copy(jobStatus = "cancelled")))
    }

    @Test
    fun `variable resolution resolves branch`() {
        assertEquals("main", parser.evaluate("branch", defaultContext))
    }

    @Test
    fun `variable resolution resolves ref`() {
        assertEquals("refs/heads/main", parser.evaluate("ref", defaultContext))
    }

    @Test
    fun `variable resolution resolves event`() {
        assertEquals("push", parser.evaluate("event", defaultContext))
    }

    @Test
    fun `variable resolution resolves matrix values`() {
        val ctx = defaultContext.copy(matrix = mapOf("java" to "17", "os" to "ubuntu"))
        assertEquals("17", parser.evaluate("matrix.java", ctx))
        assertEquals("ubuntu", parser.evaluate("matrix.os", ctx))
    }

    @Test
    fun `variable resolution resolves env values`() {
        val ctx = defaultContext.copy(env = mapOf("CI" to "true", "NODE_ENV" to "test"))
        assertEquals("true", parser.evaluate("env.CI", ctx))
        assertEquals("test", parser.evaluate("env.NODE_ENV", ctx))
    }

    @Test
    fun `variable resolution resolves secret values`() {
        val ctx = defaultContext.copy(secrets = mapOf("TOKEN" to "abc123"))
        assertEquals("abc123", parser.evaluate("secrets.TOKEN", ctx))
    }

    @Test
    fun `equality comparison with strings`() {
        assertTrue(parser.evaluateBoolean("branch == 'main'", defaultContext))
        assertFalse(parser.evaluateBoolean("branch == 'develop'", defaultContext))
    }

    @Test
    fun `inequality comparison`() {
        assertTrue(parser.evaluateBoolean("branch != 'develop'", defaultContext))
        assertFalse(parser.evaluateBoolean("branch != 'main'", defaultContext))
    }

    @Test
    fun `logical AND`() {
        assertTrue(parser.evaluateBoolean("true && true", defaultContext))
        assertFalse(parser.evaluateBoolean("true && false", defaultContext))
        assertFalse(parser.evaluateBoolean("false && true", defaultContext))
    }

    @Test
    fun `logical OR`() {
        assertTrue(parser.evaluateBoolean("true || false", defaultContext))
        assertTrue(parser.evaluateBoolean("false || true", defaultContext))
        assertFalse(parser.evaluateBoolean("false || false", defaultContext))
    }

    @Test
    fun `logical NOT`() {
        assertFalse(parser.evaluateBoolean("!true", defaultContext))
        assertTrue(parser.evaluateBoolean("!false", defaultContext))
    }

    @Test
    fun `compound expression with AND and OR`() {
        assertTrue(parser.evaluateBoolean("branch == 'main' && success()", defaultContext))
        assertFalse(parser.evaluateBoolean("branch == 'develop' && success()", defaultContext))
        assertTrue(parser.evaluateBoolean("branch == 'develop' || success()", defaultContext))
    }

    @Test
    fun `parenthesized expressions`() {
        assertTrue(parser.evaluateBoolean("(true)", defaultContext))
        assertFalse(parser.evaluateBoolean("!(true)", defaultContext))
        assertTrue(parser.evaluateBoolean("(branch == 'main') && (event == 'push')", defaultContext))
    }

    @Test
    fun `numeric comparison operators`() {
        assertTrue(parser.evaluateBoolean("1 < 2", defaultContext))
        assertFalse(parser.evaluateBoolean("2 < 1", defaultContext))
        assertTrue(parser.evaluateBoolean("2 > 1", defaultContext))
        assertTrue(parser.evaluateBoolean("2 <= 2", defaultContext))
        assertTrue(parser.evaluateBoolean("2 >= 2", defaultContext))
    }

    @Test
    fun `startsWith function`() {
        assertTrue(parser.evaluateBoolean("startsWith(ref, 'refs/heads')", defaultContext))
        assertFalse(parser.evaluateBoolean("startsWith(ref, 'refs/tags')", defaultContext))
    }

    @Test
    fun `endsWith function`() {
        assertTrue(parser.evaluateBoolean("endsWith(branch, 'ain')", defaultContext))
        assertFalse(parser.evaluateBoolean("endsWith(branch, 'dev')", defaultContext))
    }

    @Test
    fun `contains function`() {
        assertTrue(parser.evaluateBoolean("contains(ref, 'heads')", defaultContext))
        assertFalse(parser.evaluateBoolean("contains(ref, 'tags')", defaultContext))
    }

    @Test
    fun `hashFiles function delegates to fileHasher`() {
        val ctx = defaultContext.copy(fileHasher = { globs -> "sha256-${globs.joinToString(",")}" })
        assertEquals("sha256-*.kt,*.gradle", parser.evaluate("hashFiles('*.kt', '*.gradle')", ctx))
    }

    @Test
    fun `interpolate replaces expressions in template`() {
        val result = parser.interpolate("Build on \${{ branch }} via \${{ event }}", defaultContext)
        assertEquals("Build on main via push", result)
    }

    @Test
    fun `interpolate handles nested expressions`() {
        val ctx = defaultContext.copy(env = mapOf("VERSION" to "1.2.3"))
        val result = parser.interpolate("v\${{ env.VERSION }}", ctx)
        assertEquals("v1.2.3", result)
    }

    @Test
    fun `interpolate with no expressions returns unchanged string`() {
        assertEquals("hello world", parser.interpolate("hello world", defaultContext))
    }

    @Test
    fun `double quoted strings work`() {
        assertTrue(parser.evaluateBoolean("branch == \"main\"", defaultContext))
    }

    @Test
    fun `toBool coerces empty string to false`() {
        val ctx = defaultContext.copy(env = mapOf("EMPTY" to ""))
        assertFalse(parser.evaluateBoolean("env.EMPTY", ctx))
    }

    @Test
    fun `toBool coerces non-empty string to true`() {
        val ctx = defaultContext.copy(env = mapOf("NONEMPTY" to "hello"))
        assertTrue(parser.evaluateBoolean("env.NONEMPTY", ctx))
    }

    @Test
    fun `failure condition with OR allows step to run after failure`() {
        val ctx = defaultContext.copy(jobStatus = "failure")
        assertTrue(parser.evaluateBoolean("failure() || always()", ctx))
    }

    @Test
    fun `complex real-world condition`() {
        val ctx = ExpressionContext(
            ref = "refs/heads/release/2.0",
            branch = "release/2.0",
            event = "push",
            jobStatus = "success",
            env = mapOf("CI" to "true"),
        )
        assertTrue(parser.evaluateBoolean(
            "startsWith(branch, 'release/') && success() && env.CI == 'true'", ctx
        ))
    }

    @Test
    fun `unresolved variable returns null which is falsy`() {
        assertFalse(parser.evaluateBoolean("env.NONEXISTENT", defaultContext))
    }
}
