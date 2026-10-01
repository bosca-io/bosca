package bosca.graphql.codegen.cli

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** CLI edge cases: the download-schema command end-to-end over HTTP, and the option/spec parser failure paths. */
class BoscaGraphqlCodegenCliEdgeTest {

    @Test
    fun `download-schema fetches over HTTP and writes the SDL file`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/graphql") { exchange ->
            val body = """
                {"data":{"__schema":{"queryType":{"name":"Query"},"mutationType":null,"subscriptionType":null,"types":[
                  {"kind":"OBJECT","name":"Query","interfaces":[],"fields":[{"name":"ping","args":[],"type":{"kind":"SCALAR","name":"String","ofType":null}}]}
                ]}}}
            """.trimIndent().encodeToByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val out = File.createTempFile("schema", ".graphqls").apply { delete() }
        try {
            main(
                arrayOf(
                    "download-schema",
                    "--endpoint", "http://127.0.0.1:${server.address.port}/graphql",
                    "--out", out.absolutePath,
                    "--header", "Authorization: Bearer t",
                ),
            )
            assertTrue("type Query {" in out.readText(), out.readText())
        } finally {
            server.stop(0)
            out.delete()
        }
    }

    @Test
    fun `download-schema writes to a bare filename with no parent directory`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/graphql") { exchange ->
            val body = """{"data":{"__schema":{"queryType":{"name":"Query"},"types":[{"kind":"OBJECT","name":"Query","interfaces":[],"fields":[{"name":"a","args":[],"type":{"kind":"SCALAR","name":"String","ofType":null}}]}]}}}""".encodeToByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val bare = File("cli-bare-schema.graphqls") // no parent → parentFile is null
        try {
            main(arrayOf("download-schema", "--endpoint", "http://127.0.0.1:${server.address.port}/graphql", "--out", bare.name))
            assertTrue("type Query {" in bare.readText(), bare.readText())
        } finally {
            server.stop(0)
            bare.delete()
        }
    }

    @Test
    fun `generate produces kotlin and typescript clients end-to-end`() {
        val work = File.createTempFile("cli-gen", "").apply { delete(); mkdirs() }
        val schema = File(work, "schema.graphqls").apply { writeText("type Query { ping: String }\n") }
        val queries = File(work, "queries").apply { mkdirs() }
        File(queries, "ping.graphql").writeText("query Ping { ping }\n")
        val ktOut = File(work, "kt")
        val tsOut = File(work, "ts")
        try {
            main(
                arrayOf(
                    "generate", "--target", "kotlin",
                    "--schema", schema.absolutePath, "--queries", queries.absolutePath,
                    "--out", ktOut.absolutePath, "--package", "demo.client",
                    "--scalar", "DateTime:kotlinx.datetime.Instant",
                ),
            )
            assertTrue(File(ktOut, "demo/client/Ping.kt").isFile)

            // --target typescript with an explicit --runtime-module (the optional override, not the default)
            main(
                arrayOf(
                    "generate", "--target", "typescript",
                    "--schema", schema.absolutePath, "--queries", queries.absolutePath,
                    "--out", tsOut.absolutePath, "--runtime-module", "@acme/gql",
                ),
            )
            assertTrue(tsOut.listFiles()!!.any { it.extension == "ts" })

            // the "ts" alias resolves to the same TypeScript generator
            val tsAliasOut = File(work, "ts-alias")
            main(
                arrayOf(
                    "generate", "--target", "ts",
                    "--schema", schema.absolutePath, "--queries", queries.absolutePath,
                    "--out", tsAliasOut.absolutePath,
                ),
            )
            assertTrue(tsAliasOut.listFiles()!!.any { it.extension == "ts" })
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun `main rejects an unknown command and reports the offending args, or none`() {
        val unknown = assertFailsWith<IllegalStateException> { main(arrayOf("frobnicate", "x")) }
        assertTrue("frobnicate x" in unknown.message!!, unknown.message!!)
        val none = assertFailsWith<IllegalStateException> { main(emptyArray()) }
        assertTrue("<none>" in none.message!!, none.message!!) // ifEmpty fallback for an empty arg list
    }

    @Test
    fun `generate rejects an unknown target`() {
        val ex = assertFailsWith<IllegalStateException> { main(arrayOf("generate", "--target", "rust")) }
        assertTrue("rust" in ex.message!!, ex.message!!)
    }

    @Test
    fun `parseScalars handles every optional segment and rejects a single-part spec`() {
        assertFailsWith<IllegalArgumentException> { parseScalars(listOf("OnlyName")) } // < 2 parts
        val mapped = parseScalars(
            listOf(
                "Plain:kotlin.String",                                  // no import, no serializer
                "WithImport:my.Type:my.pkg.Type",                       // import only
                "Full:my.Type:my.pkg.Type:my.pkg.TypeSerializer",       // import + serializer
                "EmptyTail:my.Type::",                                   // empty import + empty serializer segments
            ),
        )
        assertEquals(emptySet(), mapped["Plain"]!!.imports)
        assertEquals(null, mapped["Plain"]!!.serializerWith)
        assertEquals(setOf("my.pkg.Type"), mapped["WithImport"]!!.imports)
        assertEquals("my.pkg.TypeSerializer", mapped["Full"]!!.serializerWith)
        assertEquals(emptySet(), mapped["EmptyTail"]!!.imports) // empty segment → takeIf drops it
        assertEquals(null, mapped["EmptyTail"]!!.serializerWith)
    }

    @Test
    fun `parseTsScalars rejects a spec that is not 2 or 4 parts`() {
        assertFailsWith<IllegalArgumentException> { parseTsScalars(listOf("A:b:c")) }
    }

    @Test
    fun `parseHeaders rejects a header without a colon`() {
        assertFailsWith<IllegalArgumentException> { parseHeaders(listOf("NoColonHere")) }
    }

    @Test
    fun `a missing required option is rejected and an optional one falls back`() {
        val options = parseOptions(listOf("--target", "kotlin"))
        assertFailsWith<IllegalStateException> { options.required("schema") }
        assertEquals("kotlin", options.optional("target"))
        assertEquals(null, options.optional("missing"))
        assertEquals(emptyList(), options.all("missing"))
    }

    @Test
    fun `parseOptions rejects a token that is not a flag`() {
        assertFailsWith<IllegalArgumentException> { parseOptions(listOf("notaflag", "value")) }
    }
}
