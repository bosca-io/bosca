package bosca.bml.contract

import kotlin.test.Test
import kotlin.test.assertEquals

class ContractParserTest {

    @Test
    fun `parses a single suspend function`() {
        val decls = ContractParser.parse(
            """interface ListOps { suspend fun reorder(ids: List<UUID>): ListView }""",
        )
        assertEquals(1, decls.size)
        val d = decls.single()
        assertEquals("ListOps", d.name)
        val f = d.functions.single()
        assertEquals("reorder", f.name)
        assertEquals(true, f.isSuspend)
        assertEquals(listOf(ContractParam("ids", "List<UUID>")), f.params)
        assertEquals("ListView", f.returnType)
    }

    @Test
    fun `parses multiple functions and multi-param generics`() {
        val decls = ContractParser.parse(
            """
            interface Catalog {
                suspend fun find(query: String, filters: Map<String, Int>): List<Item>
                fun count(): Int
            }
            """.trimIndent(),
        )
        val d = decls.single()
        assertEquals(2, d.functions.size)
        val find = d.functions[0]
        assertEquals(listOf(ContractParam("query", "String"), ContractParam("filters", "Map<String, Int>")), find.params)
        assertEquals("List<Item>", find.returnType)
        val count = d.functions[1]
        assertEquals(false, count.isSuspend)
        assertEquals(emptyList(), count.params)
        assertEquals("Int", count.returnType)
    }

    @Test
    fun `parses multiple interfaces`() {
        val decls = ContractParser.parse(
            """
            interface A { fun a(): String }
            interface B { fun b(x: Int): Boolean }
            """.trimIndent(),
        )
        assertEquals(listOf("A", "B"), decls.map { it.name })
    }
}
