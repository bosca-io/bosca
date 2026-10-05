package bosca.scripting.engine

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Validates that [ScriptSourceValidator] blocks dangerous System and Runtime usages
 * while allowing safe System methods like [System.currentTimeMillis] and [System.nanoTime].
 */
class ScriptSourceValidatorTest {

    // --- Allowed System methods ---

    @Test
    fun `allows System currentTimeMillis`() {
        ScriptSourceValidatorImpl().validate("val t = System.currentTimeMillis()")
    }

    @Test
    fun `allows System nanoTime`() {
        ScriptSourceValidatorImpl().validate("val t = System.nanoTime()")
    }

    @Test
    fun `allows System lineSeparator`() {
        ScriptSourceValidatorImpl().validate("val sep = System.lineSeparator()")
    }

    @Test
    fun `allows System identityHashCode`() {
        ScriptSourceValidatorImpl().validate("val h = System.identityHashCode(obj)")
    }

    @Test
    fun `allows System arraycopy`() {
        ScriptSourceValidatorImpl().validate("System.arraycopy(src, 0, dst, 0, len)")
    }

    // --- Blocked System methods ---

    @Test
    fun `blocks System exit`() {
        val ex = assertFailsWith<SecurityException> {
            ScriptSourceValidatorImpl().validate("System.exit(0)")
        }
        assertTrue(ex.message!!.contains("System.exit"))
    }

    @Test
    fun `blocks System getenv`() {
        val ex = assertFailsWith<SecurityException> {
            ScriptSourceValidatorImpl().validate("val e = System.getenv(\"SECRET\")")
        }
        assertTrue(ex.message!!.contains("System.getenv"))
    }

    @Test
    fun `blocks System setProperty`() {
        val ex = assertFailsWith<SecurityException> {
            ScriptSourceValidatorImpl().validate("System.setProperty(\"key\", \"value\")")
        }
        assertTrue(ex.message!!.contains("System.setProperty"))
    }

    @Test
    fun `blocks System gc`() {
        assertFailsWith<SecurityException> {
            ScriptSourceValidatorImpl().validate("System.gc()")
        }
    }

    // --- Backtick evasion ---

    @Test
    fun `blocks backtick-escaped System exit`() {
        val ex = assertFailsWith<SecurityException> {
            ScriptSourceValidatorImpl().validate("System.`exit`(0)")
        }
        assertTrue(ex.message!!.contains("exit"))
    }

    // --- Import aliasing ---

    @Test
    fun `blocks import alias of System`() {
        val ex = assertFailsWith<SecurityException> {
            ScriptSourceValidatorImpl().validate("import java.lang.System as Sys\nSys.exit(0)")
        }
        assertTrue(ex.message!!.contains("import alias"))
    }

    // --- Method references ---

    @Test
    fun `blocks System method reference`() {
        val ex = assertFailsWith<SecurityException> {
            ScriptSourceValidatorImpl().validate("val ref = System::exit")
        }
        assertTrue(ex.message!!.contains("method reference"))
    }

    // --- Bare System reference ---

    @Test
    fun `blocks bare System reference without method call`() {
        val ex = assertFailsWith<SecurityException> {
            ScriptSourceValidatorImpl().validate("val sys = System")
        }
        assertTrue(ex.message!!.contains("bare reference"))
    }

    // --- Runtime ---

    @Test
    fun `blocks Runtime reference`() {
        val ex = assertFailsWith<SecurityException> {
            ScriptSourceValidatorImpl().validate("Runtime.getRuntime().exec(\"ls\")")
        }
        assertTrue(ex.message!!.contains("Runtime"))
    }

    // --- Clean scripts ---

    @Test
    fun `allows script without System or Runtime references`() {
        ScriptSourceValidatorImpl().validate(
            """
            val x = 42
            val y = x * 2
            println(y)
            """.trimIndent()
        )
    }

    @Test
    fun `allows multiple safe System calls in same script`() {
        ScriptSourceValidatorImpl().validate(
            """
            val start = System.nanoTime()
            doSomething()
            val elapsed = System.nanoTime() - start
            """.trimIndent()
        )
    }
}
