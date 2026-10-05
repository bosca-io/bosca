package bosca.graphql.codegen.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Argument parsing + the `generate` dispatch of the CLI the Gradle plugin invokes. */
class BoscaGraphqlCodegenCliTest {

    @Test
    fun `parseScalars handles 2 to 4 part specs`() {
        val mappings = parseScalars(
            listOf(
                "Long:Long",
                "DateTime:Instant:kotlinx.datetime.Instant:kotlinx.datetime.serializers.InstantIso8601Serializer",
            ),
        )
        assertEquals("Long", mappings.getValue("Long").kotlinType)
        val dateTime = mappings.getValue("DateTime")
        assertEquals("Instant", dateTime.kotlinType)
        assertEquals(setOf("kotlinx.datetime.Instant"), dateTime.imports)
        assertEquals("kotlinx.datetime.serializers.InstantIso8601Serializer", dateTime.serializerWith)
    }

    @Test
    fun `parseHeaders splits on the first colon`() {
        assertEquals("Bearer a:b", parseHeaders(listOf("Authorization: Bearer a:b")).getValue("Authorization"))
    }

    @Test
    fun `parseOptions rejects a dangling flag`() {
        assertFailsWith<IllegalArgumentException> { parseOptions(listOf("--schema")) }
    }

    @Test
    fun `parseOptions accumulates repeated flags`() {
        val options = parseOptions(listOf("--scalar", "A:A", "--scalar", "B:B"))
        assertEquals(listOf("A:A", "B:B"), options.all("scalar"))
    }

    @Test
    fun `main generate end-to-end with a custom scalar`() {
        val root = Files.createTempDirectory("gqlc-cli").toFile()
        try {
            File(root, "schema.graphqls").writeText("type Query { score: Long } scalar Long")
            File(root, "graphql").mkdirs()
            File(root, "graphql/Q.graphql").writeText("query Q { score }")
            main(
                arrayOf(
                    "generate",
                    "--schema", "$root/schema.graphqls",
                    "--queries", "$root/graphql",
                    "--out", "$root/out",
                    "--package", "gen",
                    "--scalar", "Long:Long",
                ),
            )
            assertTrue("val score: Long?" in File(root, "out/gen/Q.kt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `parseTsScalars handles 2 and 4 part specs`() {
        val mappings = parseTsScalars(listOf("Long:number", "DateTime:Instant:Instant:@js-joda/core"))
        assertEquals("number", mappings.getValue("Long").tsType)
        val dateTime = mappings.getValue("DateTime")
        assertEquals("Instant", dateTime.tsType)
        assertEquals("Instant", dateTime.importName)
        assertEquals("@js-joda/core", dateTime.importFrom)
    }

    @Test
    fun `main generate with the typescript target writes ts files`() {
        val root = Files.createTempDirectory("gqlc-ts").toFile()
        try {
            File(root, "schema.graphqls").writeText("type Query { now: String! }")
            File(root, "graphql").mkdirs()
            File(root, "graphql/Now.graphql").writeText("query Now { now }")
            main(
                arrayOf(
                    "generate",
                    "--target", "typescript",
                    "--schema", "$root/schema.graphqls",
                    "--queries", "$root/graphql",
                    "--out", "$root/out",
                ),
            )
            val ts = File(root, "out/Now.ts").readText()
            assertTrue("export function now(): Promise<NowData>" in ts, ts)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `main rejects an unknown target`() {
        assertFailsWith<IllegalStateException> {
            main(arrayOf("generate", "--target", "rust", "--schema", "x", "--queries", "y", "--out", "z"))
        }
    }

    @Test
    fun `main rejects an unknown command`() {
        assertFailsWith<IllegalStateException> { main(arrayOf("frobnicate")) }
    }
}
