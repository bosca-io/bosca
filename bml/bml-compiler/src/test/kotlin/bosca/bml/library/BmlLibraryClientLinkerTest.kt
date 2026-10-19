package bosca.bml.library

import bosca.bml.project.ContentDigest
import java.io.File
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BmlLibraryClientLinkerTest {
    @Test
    fun `binary declarations are verified linked only as type inputs and removed when unused`() {
        val root = createTempDirectory("bml-library-declarations").toFile()
        try {
            val component = BmlImportFixtures.component().copy(clientModule = null)
            val manifest = BmlImportFixtures.manifest(components = listOf(component))
            val path = "client/contracts/selection.d.ts"
            val declaration = "declare module '@example/design/select' { export interface Selection { value: string } }"
            val key = BmlLibraryClientManifest.namespace(manifest.library, manifest.version)
            val client = BmlLibraryClientManifest(1, manifest.library, manifest.version,
                "bml/libraries/$key", "/_bml/libraries/$key", emptyMap(), emptyList(), emptyList(),
                listOf(BmlAssetFile(path, ContentDigest.sha256(declaration))))
            val entries = mapOf(
                BmlLibraryArtifacts.MANIFEST_PATH to BmlLibraryArtifacts.encode(manifest),
                BmlLibraryClientManifest.PATH to Json.encodeToString(BmlLibraryClientManifest.serializer(), client),
                "${client.resourcePrefix}/$path" to declaration,
            )
            val archive = BmlImportFixtures.archive(File(root, "design.jar"), entries)
            val dependency = BmlLibraryDependency(archive, manifest)
            val selected = listOf(BmlLibraryGraph.Component(dependency, component))
            val ts = File(root, "ts").apply { mkdirs() }
            val resources = File(root, "resources")
            fun link(selection: List<BmlLibraryGraph.Component> = selected) = BmlLibraryClientLinker.link(
                selection, emptyList(), emptyList(), ts, resources, listOf(dependency),
            )

            link()
            val extracted = File(ts, "libraries/$key/$path")
            assertEquals(declaration, extracted.readText())
            assertFalse(File(resources, "libraries/$key/$path").exists())
            link(emptyList())
            assertFalse(extracted.exists(), "Removing the last selected component must remove its declarations")

            for (corrupt in listOf(entries - "${client.resourcePrefix}/$path",
                entries + ("${client.resourcePrefix}/$path" to "changed"))) {
                BmlImportFixtures.archive(archive, corrupt)
                val failure = assertFailsWith<IllegalArgumentException> { link() }
                assertTrue(path in failure.message.orEmpty(), failure.message)
            }
        } finally { root.deleteRecursively() }
    }
}
