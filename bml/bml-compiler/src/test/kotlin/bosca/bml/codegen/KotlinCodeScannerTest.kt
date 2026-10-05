package bosca.bml.codegen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinCodeScannerTest {
    private fun code(source: String): String = KotlinCodeScanner(source).codeWithoutLiterals()

    @Test
    fun `removes comments including nested block comments`() {
        val result = code("a // ctx.featureFlags\nb /* outer /* ctx.featureFlags */ still comment */ c")
        assertFalse("featureFlags" in result, result)
        assertTrue("a" in result && "b" in result && result.trimEnd().endsWith("c"), result)
    }

    @Test
    fun `removes string and character literal contents`() {
        val result = code("""f("ctx.featureFlags \" quoted", '"', '\'', ""${'"'}raw ctx.featureFlags""${'"'}) + g""")
        assertFalse("featureFlags" in result, result)
        assertTrue(result.trimEnd().endsWith("g"), result)
    }

    @Test
    fun `keeps string template expressions as code`() {
        val result = code(""""on=${'$'}{ctx.featureFlags.enabled("a")} ${'$'}{ mapOf(1 to 2).let { it.size } }" + tail""")
        assertTrue("ctx.featureFlags.enabled(" in result, result)
        assertTrue("it.size" in result, result)
        assertTrue(result.trimEnd().endsWith("tail"), result)
    }

    @Test
    fun `raw strings may end with extra quote characters`() {
        // `""""` closes a raw string whose content ends with one quote character.
        val result = code("\"\"\"text\"\"\"\" + x")
        assertFalse("text" in result || '"' in result, result)
        assertEquals("+ x", result.trim())
    }
}
