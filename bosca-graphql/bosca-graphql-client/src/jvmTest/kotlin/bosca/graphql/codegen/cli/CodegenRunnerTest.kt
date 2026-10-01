package bosca.graphql.codegen.cli

import bosca.graphql.codegen.ScalarMapping
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** File-I/O behaviour of the codegen runner: reads the schema + `.graphql` tree, writes one file per op, prunes stale output. */
class CodegenRunnerTest {

    private fun withTempProject(block: (root: File) -> Unit) {
        val root = Files.createTempDirectory("gqlc").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `reads a schema and queries tree and writes one file per operation`() = withTempProject { root ->
        val schema = File(root, "schema.graphqls").apply {
            writeText("type Query { user(id: ID!): User } type User { id: ID! name: String! }")
        }
        val queries = File(root, "graphql").apply { mkdirs() }
        File(queries, "GetUser.graphql").writeText("query GetUser(\$id: ID!) { user(id: \$id) { id name } }")
        File(queries, "frag.graphql").writeText("fragment U on User { id }") // fragment-only file → no operation file
        val out = File(root, "out")

        val written = CodegenRunner.generate(schema, queries, out, "gen")

        assertEquals(listOf("GetUser.kt"), written.map { it.name })
        val file = File(out, "gen/GetUser.kt")
        assertTrue(file.isFile)
        assertTrue("object GetUser : BoscaOperation" in file.readText())
    }

    @Test
    fun `removes stale generated kotlin before writing`() = withTempProject { root ->
        val schema = File(root, "schema.graphqls").apply { writeText("type Query { now: String! }") }
        val queries = File(root, "graphql").apply { mkdirs() }
        File(queries, "Now.graphql").writeText("query Now { now }")
        val out = File(root, "out")
        val stale = File(out, "gen/Old.kt").apply { parentFile.mkdirs(); writeText("// stale") }

        CodegenRunner.generate(schema, queries, out, "gen")

        assertFalse(stale.exists()) // pruned so a removed operation doesn't linger
        assertTrue(File(out, "gen/Now.kt").isFile)
    }

    @Test
    fun `applies custom scalar mappings`() = withTempProject { root ->
        val schema = File(root, "schema.graphqls").apply { writeText("type Query { at: DateTime } scalar DateTime") }
        val queries = File(root, "graphql").apply { mkdirs() }
        File(queries, "Q.graphql").writeText("query Q { at }")
        val out = File(root, "out")

        CodegenRunner.generate(schema, queries, out, "gen", mapOf("DateTime" to ScalarMapping("Long")))
        assertTrue("val at: Long?" in File(out, "gen/Q.kt").readText())
    }

    @Test
    fun `generates TypeScript files for the ts target`() = withTempProject { root ->
        val schema = File(root, "schema.graphqls").apply {
            writeText("type Query { user(id: ID!): User } type User { id: ID! name: String! }")
        }
        val queries = File(root, "graphql").apply { mkdirs() }
        File(queries, "GetUser.graphql").writeText("query GetUser(\$id: ID!) { user(id: \$id) { id name } }")
        val out = File(root, "out")

        val written = CodegenRunner.generateTypeScript(schema, queries, out, runtimeModule = "../runtime")

        assertEquals(listOf("GetUser.ts"), written.map { it.name })
        val ts = File(out, "GetUser.ts").readText()
        assertTrue("""import { bosca } from "../runtime"""" in ts, ts)
        assertTrue("export function getUser(variables: GetUserVariables): Promise<GetUserData>" in ts, ts)
    }

    @Test
    fun `fails when no operations are found`() = withTempProject { root ->
        val schema = File(root, "schema.graphqls").apply { writeText("type Query { now: String! }") }
        val queries = File(root, "graphql").apply { mkdirs() }
        assertFailsWith<IllegalArgumentException> { CodegenRunner.generate(schema, queries, File(root, "out"), "gen") }
    }

    @Test
    fun `fails when the schema file or queries dir does not exist`() = withTempProject { root ->
        val schema = File(root, "schema.graphqls").apply { writeText("type Query { now: String! }") }
        val queries = File(root, "graphql").apply { mkdirs() }
        File(queries, "Now.graphql").writeText("query Now { now }")
        // a schema path that is not a file
        assertFailsWith<IllegalArgumentException> {
            CodegenRunner.generate(File(root, "absent.graphqls"), queries, File(root, "out"), "gen")
        }
        // a queries path that is not a directory
        assertFailsWith<IllegalArgumentException> {
            CodegenRunner.generate(schema, File(root, "absent-dir"), File(root, "out"), "gen")
        }
    }

    @Test
    fun `generateTypeScript falls back to the default runtime module`() = withTempProject { root ->
        val schema = File(root, "schema.graphqls").apply { writeText("type Query { now: String! }") }
        val queries = File(root, "graphql").apply { mkdirs() }
        File(queries, "Now.graphql").writeText("query Now { now }")
        val out = File(root, "out")

        CodegenRunner.generateTypeScript(schema, queries, out) // no runtimeModule arg → default "@bosca/bml"

        assertTrue("""from "@bosca/bml"""" in File(out, "Now.ts").readText())
    }
}
