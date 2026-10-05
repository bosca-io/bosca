package bosca.kubernetes.controller.helm

import bosca.kubernetes.model.HelmRepoCredentials
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Credentials
import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.ByteArrayOutputStream
import java.time.OffsetDateTime
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [HelmIndexFetcher]. The fetcher reaches over plain HTTP to
 * a Helm repo's index endpoint and to chart tarballs. The contract
 * worth pinning:
 *
 *   * `fetchIndex` GETs `<repoUrl>/index.yaml` and returns the body
 *     verbatim; non-2xx throws so callers can surface a wire error.
 *   * `fetchValues` extracts `values.yaml` and the optional
 *     `values.schema.json` out of the chart tarball.
 *   * Basic credentials are scoped to the configured repository origin.
 *   * `toRepoView` stitches a stored row + cached index into the wire
 *     shape; `lastUpdate` is `-` when the index hasn't been pulled yet.
 */
class HelmIndexFetcherTest {

    private val server = MockWebServer().apply { start() }
    private val base = server.url("").toString().trimEnd('/')
    private val fetcher = HelmIndexFetcher(http = OkHttpClient())

    @AfterTest
    fun teardown() {
        server.close()
        io.mockk.unmockkAll()
    }

    @Test
    fun `fetchIndex GETs slash index dot yaml and returns the body`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("apiVersion: v1\nentries: {}\n")
                .build()
        )
        val body = fetcher.fetchIndex(base)
        assertEquals("apiVersion: v1\nentries: {}\n", body)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/index.yaml", req.target)
    }

    @Test
    fun `fetchIndex tolerates a trailing slash on the repo URL`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        fetcher.fetchIndex("$base/")
        val req = server.takeRequest()
        assertEquals("/index.yaml", req.target)
    }

    @Test
    fun `fetchIndex sends supplied Basic credentials`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())

        fetcher.fetchIndex(base, HelmRepoCredentials("api_token", "bsk_secret"))

        val req = server.takeRequest()
        assertEquals(Credentials.basic("api_token", "bsk_secret"), req.headers["Authorization"])
    }

    @Test
    fun `fetchIndex throws with body code in the message on non-2xx`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).body("not here").build())
        val ex = assertFailsWith<IllegalStateException> { fetcher.fetchIndex(base) }
        assertTrue(ex.message!!.contains("404"))
    }

    @Test
    fun `fetchValues extracts both values yaml and schema json from the tarball`() = runTest {
        val tgz = chartTarball(
            entries = mapOf(
                "podinfo/Chart.yaml" to "name: podinfo\n",
                "podinfo/values.yaml" to "replicaCount: 3\n",
                "podinfo/values.schema.json" to """{"type":"object"}""",
            )
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(Buffer().apply { write(tgz) })
                .build()
        )
        val values = fetcher.fetchValues(server.url("/podinfo-1.0.0.tgz").toString())
        assertEquals("replicaCount: 3\n", values.defaultValues)
        val schema = values.schema
        assertNotNull(schema)
        assertIs<JsonObject>(schema)
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `fetchValues returns empty defaults when values yaml is absent`() = runTest {
        val tgz = chartTarball(mapOf("podinfo/Chart.yaml" to "name: podinfo\n"))
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(Buffer().apply { write(tgz) })
                .build()
        )
        val values = fetcher.fetchValues(server.url("/x.tgz").toString())
        assertEquals("", values.defaultValues)
        assertNull(values.schema)
    }

    @Test
    fun `fetchValues throws with code in the message on non-2xx`() = runTest {
        server.enqueue(MockResponse.Builder().code(500).build())
        val ex = assertFailsWith<IllegalStateException> {
            fetcher.fetchValues(server.url("/x.tgz").toString())
        }
        assertTrue(ex.message!!.contains("500"))
    }

    @Test
    fun `fetchValues ignores schema entries that aren't valid JSON`() = runTest {
        val tgz = chartTarball(
            entries = mapOf(
                "podinfo/values.yaml" to "v: 1\n",
                "podinfo/values.schema.json" to "{ this is not json",
            )
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(Buffer().apply { write(tgz) })
                .build()
        )
        val values = fetcher.fetchValues(server.url("/x.tgz").toString())
        assertEquals("v: 1\n", values.defaultValues)
        assertNull(values.schema, "malformed schema json drops to null rather than failing the fetch")
    }

    @Test
    fun `fetchValues accepts top-level values yaml without a chart directory prefix`() = runTest {
        val tgz = chartTarball(
            entries = mapOf(
                "values.yaml" to "topLevel: true\n",
                "values.schema.json" to """{"ok":true}""",
            )
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(Buffer().apply { write(tgz) })
                .build()
        )
        val values = fetcher.fetchValues(server.url("/x.tgz").toString())
        assertEquals("topLevel: true\n", values.defaultValues)
        assertNotNull(values.schema)
    }

    @Test
    fun `fetchValues sends credentials to a chart on the repository origin`() = runTest {
        val tgz = chartTarball(mapOf("podinfo/values.yaml" to "authenticated: true\n"))
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().apply { write(tgz) }).build())

        fetcher.fetchValues(
            tgzUrl = server.url("/charts/podinfo-1.0.0.tgz").toString(),
            repoUrl = base,
            credentials = HelmRepoCredentials("api_token", "bsk_secret"),
        )

        val req = server.takeRequest()
        assertEquals(Credentials.basic("api_token", "bsk_secret"), req.headers["Authorization"])
    }

    @Test
    fun `fetchValues resolves relative chart URLs against the repository`() = runTest {
        val tgz = chartTarball(mapOf("podinfo/values.yaml" to "relative: true\n"))
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().apply { write(tgz) }).build())

        fetcher.fetchValues("charts/podinfo-1.0.0.tgz", repoUrl = base)

        assertEquals("/charts/podinfo-1.0.0.tgz", server.takeRequest().target)
    }

    @Test
    fun `fetchValues does not leak repository credentials to a different origin`() = runTest {
        val tgz = chartTarball(mapOf("podinfo/values.yaml" to "external: true\n"))
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().apply { write(tgz) }).build())
        val externalUrl = server.url("/external/podinfo-1.0.0.tgz").toString()
        val repositoryUrl = externalUrl.replace("localhost", "127.0.0.1").substringBefore("/external")

        fetcher.fetchValues(
            tgzUrl = externalUrl,
            repoUrl = repositoryUrl,
            credentials = HelmRepoCredentials("api_token", "bsk_secret"),
        )

        assertNull(server.takeRequest().headers["Authorization"])
    }

    // ===== toRepoView =====

    @Test
    fun `toRepoView counts charts and renders relative lastUpdate`() {
        val now = OffsetDateTime.parse("2026-05-15T12:00:00Z")
        val view = fetcher.toRepoView(
            name = "podinfo",
            url = "https://stefanprodan.github.io/podinfo",
            type = "https",
            indexYaml = """
                apiVersion: v1
                entries:
                  podinfo: []
                  redis: []
            """.trimIndent(),
            lastIndexAt = now.minusHours(2),
            now = now,
        )
        assertEquals("podinfo", view.name)
        assertEquals("https", view.type)
        assertEquals(2, view.charts)
        assertEquals("2h ago", view.lastUpdate)
    }

    @Test
    fun `toRepoView renders dash for missing lastIndexAt`() {
        val view = fetcher.toRepoView(
            name = "n", url = "u", type = "https",
            indexYaml = null, lastIndexAt = null,
        )
        assertEquals("-", view.lastUpdate)
        assertEquals(0, view.charts)
    }

    /**
     * Builds a tar.gz blob in memory with the given path → content
     * entries. Mimics the layout of a real chart tarball just enough
     * for the fetcher's entry-name predicates to match.
     */
    private fun chartTarball(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                for ((path, content) in entries) {
                    val bytes = content.toByteArray()
                    val entry = TarArchiveEntry(path)
                    entry.size = bytes.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
                tar.finish()
            }
        }
        // Just to make sure okio is on the classpath for the test infrastructure.
        @Suppress("UNUSED_VARIABLE") val ignored = byteArrayOf().toByteString()
        return out.toByteArray()
    }
}
