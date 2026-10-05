package bosca.graphql.schema

import bosca.graphql.parser.Parser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * false-positive guard: the hardened [SchemaBuilder] must accept the real composed Bosca schema
 * (the gateway-stitched SDL checked in at `cli/src/main/graphql/schema.graphqls`) without error. Runs only
 * when that file is present (i.e. in the full workspace), which is where it matters.
 */
class RealComposedSchemaTest {

    @Test
    fun `the real composed Bosca schema parses and validates cleanly`() {
        val schemaFile = File("../../cli/src/main/graphql/schema.graphqls")
        if (!schemaFile.isFile) {
            println("Skipping: ${schemaFile.absolutePath} not present (partial checkout)")
            return
        }
        val schema = GraphQLSchema.fromDocument(Parser.parse(schemaFile.readText()))
        assertTrue(schema.queryType != null, "expected a query root type in the composed schema")
        assertTrue(schema.types.size > 500, "expected the full composed type system, got ${schema.types.size}")
    }
}
