package bosca.bml.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Branch coverage for [ContractParser]: defaults, suspend detection, generics, whole-word matching, recovery. */
class ContractParserBranchesTest {

    @Test fun `function with no return type defaults to Unit and is not suspend`() {
        val fn = ContractParser.parse("interface S {\n  fun go()\n}")[0].functions[0]
        assertEquals("Unit", fn.returnType)
        assertFalse(fn.isSuspend)
        assertTrue(fn.params.isEmpty())
    }

    @Test fun `suspend modifier is detected`() {
        assertTrue(ContractParser.parse("interface S {\n  suspend fun go(): Int\n}")[0].functions[0].isSuspend)
    }

    @Test fun `generic param and return types are not split on inner commas`() {
        val fn = ContractParser.parse("interface S {\n  fun f(m: Map<String, Int>, x: Int): List<Pair<A, B>>\n}")[0].functions[0]
        assertEquals(2, fn.params.size)
        assertEquals("Map<String, Int>", fn.params[0].type)
        assertEquals("x", fn.params[1].name)
        assertEquals("List<Pair<A, B>>", fn.returnType)
    }

    @Test fun `interface as part of a larger identifier is not matched`() {
        assertTrue(ContractParser.parse("val myinterfaces = listOf(1)").isEmpty())
    }

    @Test fun `fun inside a larger identifier is not matched`() {
        assertTrue(ContractParser.parse("interface S {\n  val functions = 1\n}")[0].functions.isEmpty())
    }

    @Test fun `interface with no name or body is skipped`() {
        assertTrue(ContractParser.parse("interface ").isEmpty())
    }

    @Test fun `multiple interfaces, the second still parses after the first`() {
        val ds = ContractParser.parse("interface A {\n  fun a(): Int\n}\ninterface B {\n  fun b(): String\n}")
        assertEquals(listOf("A", "B"), ds.map { it.name })
    }

    @Test fun `unterminated interface body still parses its functions`() {
        val ds = ContractParser.parse("interface S {\n  fun a(): Int\n")
        assertEquals(1, ds.size)
        assertEquals("a", ds[0].functions[0].name)
    }

    @Test fun `param without a type colon is dropped`() {
        assertTrue(ContractParser.parse("interface S {\n  fun f(justName): Int\n}")[0].functions[0].params.isEmpty())
    }

    @Test fun `a function with an unterminated parameter list is skipped`() {
        assertTrue(ContractParser.parse("interface S {\n  fun a(x: Int\n}")[0].functions.isEmpty())
    }

    @Test fun `return type with generics is captured`() {
        assertEquals("Map<String, List<Int>>", ContractParser.parse("interface S {\n  fun f(): Map<String, List<Int>>\n}")[0].functions[0].returnType)
    }
}
