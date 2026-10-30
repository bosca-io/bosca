package bosca.git.server

import bosca.bml.library.BmlApplicationAssets
import bosca.bml.library.BmlClientManifest
import bosca.bml.project.ContentDigest
import java.io.File
import java.net.URI
import java.time.Instant
import java.util.jar.JarFile
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.*

/** Checks native embedding origins and sizes against the application and library JAR inputs. */
internal fun verifyGitServerNativeResources(report: File, artifacts: File) {
    report.copyTo(File(artifacts, "embedded-resources.json"), overwrite = true)
    val resources = Json.parseToJsonElement(report.readText()).jsonArray
        .associateBy { it.jsonObject.getValue("name").jsonPrimitive.content }
    val loader = GitServerIntegrationTest::class.java.classLoader
    val expected = linkedMapOf<String, List<String>>()
    // The host manifest includes consumed library assets under the application resource root.
    val root = "bml/applications/bml.generated"
    val manifestPath = "$root/client-manifest.json"
    val manifestBytes = requireNotNull(loader.getResourceAsStream(manifestPath)).use { it.readAllBytes() }
    val manifest = Json.decodeFromString(BmlClientManifest.serializer(), manifestBytes.decodeToString())
    BmlApplicationAssets.validateFiles(manifest.files)
    val indexedAssets = manifest.files.size
    expected.putAll(manifest.files.associate { "$root/${it.path}" to listOf(it.digest) })
    expected[manifestPath] = listOf(ContentDigest.sha256(manifestBytes))
    for (name in listOf("geist", "lucide", "source-view", "json-editor")) {
        val path = "META-INF/licenses/bml-design/$name.txt"
        expected[path] = listOf(requireNotNull(loader.getResourceAsStream(path)).use { ContentDigest.sha256(it.readAllBytes()) })
    }
    for (path in listOf("git-ui.css", "git-ui.js", "auth-ui.css")) {
        expected[path] = listOf(requireNotNull(loader.getResourceAsStream(path)).use { ContentDigest.sha256(it.readAllBytes()) })
    }
    expected["META-INF/bosca/bml/build-id"] = listOf("host", "ui").map { host ->
        val bytes = File(requireNotNull(System.getProperty("gitServer.native.${host}BuildId"))).readBytes()
        Instant.parse(bytes.decodeToString())
        ContentDigest.sha256(bytes)
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
        put("evidence", "Compiler-reported embedding origins and sizes; hashes verify input JAR bytes. HTTP font bytes are verified separately.")
        put("indexedAssets", indexedAssets)
        put("resources", JsonObject(verified))
    }.toString())
}
