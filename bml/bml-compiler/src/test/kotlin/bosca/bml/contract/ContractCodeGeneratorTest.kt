package bosca.bml.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContractCodeGeneratorTest {

    private val listOps = ContractParser.parse(
        """interface ListOps { suspend fun reorder(ids: List<UUID>): ListView }""",
    ).single()

    @Test
    fun `maps kotlin types to typescript`() {
        assertEquals("string", ContractCodeGenerator.tsType("UUID"))
        assertEquals("number", ContractCodeGenerator.tsType("Int"))
        assertEquals("boolean", ContractCodeGenerator.tsType("Boolean"))
        assertEquals("string[]", ContractCodeGenerator.tsType("List<UUID>"))
        assertEquals("string | null", ContractCodeGenerator.tsType("String?"))
        assertEquals("Record<string, number>", ContractCodeGenerator.tsType("Map<String, Int>"))
        assertEquals("unknown", ContractCodeGenerator.tsType("ListView"))
    }

    @Test
    fun `generates a typed typescript client`() {
        val ts = ContractCodeGenerator.generateTypeScript(listOps)
        assertTrue(ts.contains("export interface ListOps {"), ts)
        assertTrue(ts.contains("reorder(ids: string[]): Promise<unknown>"), ts)
        assertTrue(ts.contains("export const ListOps: ListOps = {"), ts)
        assertTrue(ts.contains("""reorder: (ids) => bmlContractCall("ListOps", "reorder", [ids]),"""), ts)
    }

    @Test
    fun `generates the contract interface and its decode-invoke-encode dispatcher`() {
        val kt = ContractCodeGenerator.generateServerDispatcher(listOps, "bml.generated")
        assertTrue(kt.contains("package bml.generated"), kt)
        assertTrue(kt.contains("@BmlContract"), kt)
        // The site implements this interface; data access goes through the caller's GraphQL client.
        assertTrue(kt.contains("public interface ListOps {"), kt)
        assertTrue(kt.contains("public suspend fun reorder(gql: bosca.bml.graphql.GraphQLClient, ids: List<UUID>): ListView"), kt)
        // The dispatcher decodes the JSON args array, invokes, and encodes the result.
        assertTrue(kt.contains("public class ListOpsDispatcher("), kt)
        assertTrue(kt.contains(": bosca.bml.render.BmlContractDispatcher"), kt)
        assertTrue(kt.contains("""listOf("reorder")"""), kt)
        assertTrue(kt.contains("""impl.reorder(gql, json.decodeFromJsonElement<List<UUID>>(args[0]))"""), kt)
    }

    @Test
    fun `a Unit-returning contract method dispatches to a json null`() {
        val decl = ContractParser.parse(
            """interface Pings { suspend fun ping(name: String) }""",
        ).single()
        val kt = ContractCodeGenerator.generateServerDispatcher(decl, "bml.generated")
        assertTrue(kt.contains(""""ping" -> { impl.ping(gql, json.decodeFromJsonElement<String>(args[0])); "null" }"""), kt)
    }
}
