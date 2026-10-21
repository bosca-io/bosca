package bosca.git.ui

import bosca.bml.library.BmlApplicationAssets
import bosca.bml.library.BmlClientManifest
import bosca.bml.project.ContentDigest
import java.io.File
import java.net.URI
import java.util.jar.JarFile
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Verifies compiler-reported resource origins and sizes against digest-checked publication inputs. */
internal fun verifyGitUiNativeResources(report: File, retainedReport: File) {
    val resources = Json.parseToJsonElement(report.readText()).jsonArray
        .associateBy { it.jsonObject.getValue("name").jsonPrimitive.content }
    val loader = GitUiNativeHostTest::class.java.classLoader
    val root = "bml/applications/bml.generated"
    val manifestPath = "$root/client-manifest.json"
    val manifestBytes = requireNotNull(loader.getResourceAsStream(manifestPath)).use { it.readAllBytes() }
    val manifest = Json.decodeFromString(BmlClientManifest.serializer(), manifestBytes.decodeToString())
    BmlApplicationAssets.validateFiles(manifest.files)
    val expected = manifest.files.associate { "$root/${it.path}" to it.digest }.toMutableMap()
    expected[manifestPath] = ContentDigest.sha256(manifestBytes)
    for (name in listOf("geist", "lucide", "source-view", "json-editor")) {
        val path = "META-INF/licenses/bml-design/$name.txt"
        expected[path] = requireNotNull(loader.getResourceAsStream(path)).use { ContentDigest.sha256(it.readAllBytes()) }
    }
    for (name in listOf("git-ui.css", "git-ui.js")) {
        expected[name] = requireNotNull(loader.getResourceAsStream(name)).use { ContentDigest.sha256(it.readAllBytes()) }
    }
    val verified = expected.mapValues { (path, digest) ->
        val resource = assertNotNull(resources[path], "Native resource missing: $path").jsonObject
        val entry = resource.getValue("entries").jsonArray.single().jsonObject
        val origin = entry.getValue("origin").jsonPrimitive.content
        val bytes = JarFile(File(URI(origin))).use { jar ->
            val file = assertNotNull(jar.getJarEntry(path), "$origin: $path")
            jar.getInputStream(file).use { it.readAllBytes() }
        }
        assertEquals(entry.getValue("size").jsonPrimitive.long, bytes.size.toLong(), path)
        assertEquals(digest, ContentDigest.sha256(bytes), "$path native build input")
        buildJsonObject {
            put("origin", origin)
            put("reportedSize", bytes.size)
            put("inputSha256", digest)
        }
    }
    report.copyTo(retainedReport, overwrite = true)
    File(retainedReport.parentFile, "resource-origins.json").writeText(buildJsonObject {
        put("evidence", "Compiler-reported embedding origins and sizes; SHA-256 values verify those input bytes. HTTP font bytes are verified separately.")
        put("indexedAssets", manifest.files.size)
        put("resources", JsonObject(verified))
    }.toString())
}
