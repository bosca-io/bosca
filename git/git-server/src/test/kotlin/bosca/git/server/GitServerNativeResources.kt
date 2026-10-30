package bosca.git.server

import bosca.bml.project.ContentDigest
import java.io.File
import java.net.URI
import java.util.jar.JarFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlinx.serialization.json.*

/** Checks native embedding origins and sizes against the application and library JAR inputs. */
internal fun verifyGitServerNativeResources(report: File, artifacts: File) {
    report.copyTo(File(artifacts, "embedded-resources.json"), overwrite = true)
    val resources = Json.parseToJsonElement(report.readText()).jsonArray
        .associateBy { it.jsonObject.getValue("name").jsonPrimitive.content }
    val loader = GitServerIntegrationTest::class.java.classLoader
    val expected = linkedMapOf<String, List<String>>()
    assertTrue(resources.keys.none { it.startsWith("bml/") || it.startsWith("META-INF/bml/") ||
        it == "META-INF/bosca/bml/build-id" || it == "git-ui.css" || it == "git-ui.js" },
        "Backend-only Git must not embed a BML application")
    for (path in listOf("application.yaml", "graphql/git-server.graphqls", "db/migrations/V49__pull_request_merge_base.sql")) {
        expected[path] = listOf(requireNotNull(loader.getResourceAsStream(path)).use { ContentDigest.sha256(it.readAllBytes()) })
    }
    val verified = expected.mapValues { (path, digests) ->
        val resource = assertNotNull(resources[path], "Native resource missing: $path").jsonObject
        val entries = resource.getValue("entries").jsonArray.map { value ->
            val entry = value.jsonObject
            val origin = entry.getValue("origin").jsonPrimitive.content
            val bytes = JarFile(File(URI(origin))).use { jar ->
                val file = assertNotNull(jar.getJarEntry(path), "$origin: $path")
                jar.getInputStream(file).use { it.readAllBytes() }
            }
            assertEquals(entry.getValue("size").jsonPrimitive.long, bytes.size.toLong(), path)
            buildJsonObject {
                put("origin", origin)
                put("reportedSize", bytes.size)
                put("inputSha256", ContentDigest.sha256(bytes))
            }
        }
        assertEquals(digests.sorted(), entries.map { it.getValue("inputSha256").jsonPrimitive.content }.sorted(), path)
        JsonArray(entries)
    }
    File(artifacts, "resource-origins.json").writeText(buildJsonObject {
        put("evidence", "Compiler-reported backend resource origins, sizes and input JAR digests; no BML UI assets.")
        put("resources", JsonObject(verified))
    }.toString())
}
