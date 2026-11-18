package bosca.bml.library

import bosca.bml.project.ContentDigest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class BmlApplicationAssetsTest {
    @Test
    fun `indexed validators require no resource body and each read still checks exact bytes`() {
        var reads = 0
        val body = "original".encodeToByteArray()
        val digest = ContentDigest.sha256(body)
        val manifest = Json.encodeToString(BmlClientManifest.serializer(), BmlClientManifest(files = listOf(BmlAssetFile("prod/page.js", digest))))
        val assets = BmlApplicationAssets("app", emptyMap(), emptyMap()) { path ->
            if (path.endsWith("client-manifest.json")) manifest.encodeToByteArray()
            else { reads++; body.copyOf() }
        }
        assertEquals(digest, assets.digest("prod/page.js"))
        assertEquals(setOf("prod/page.js"), assets.paths)
        assertNull(assets.digest("../prod/page.js"))
        assertNull(assets.digest("unknown"))
        assertEquals(0, reads)
        assertContentEquals(body, assets.read("prod/page.js"))
        assertEquals(1, reads)
        body[0] = 0
        assertFailsWith<IllegalArgumentException> { assets.read("prod/page.js") }
    }
    @Test
    fun `only indexed digest checked bytes can be read and every resource affects the revision`() {
        val bytes = byteArrayOf(0, 1, -1)
        fun reader(content: ByteArray): BmlApplicationAssets {
            val manifest = Json.encodeToString(BmlClientManifest.serializer(), BmlClientManifest(files = listOf(
                BmlAssetFile("libraries/key/font.woff2", ContentDigest.sha256(content)),
            )))
            return BmlApplicationAssets("app", emptyMap(), emptyMap()) { path ->
                when (path) {
                    "app/client-manifest.json" -> manifest.encodeToByteArray()
                    "app/libraries/key/font.woff2" -> bytes
                    else -> error("Unexpected resource read: $path")
                }
            }
        }
        val original = reader(bytes)
        assertContentEquals(bytes, original.read("libraries/key/font.woff2"))
        for (path in listOf("../secret", "/secret", "dev//file.js", "dev/./file.js", "dev/%2e%2e/file", "dev\\file.js", "unknown.js")) {
            assertNull(original.read(path), path)
        }
        val changed = reader(byteArrayOf(2))
        assertNotEquals(original.revision, changed.revision)
        assertFailsWith<IllegalArgumentException> { changed.read("libraries/key/font.woff2") }
        assertEquals("", BmlApplicationAssets("app", emptyMap(), emptyMap()) { null }.revision)
    }

    @Test
    fun `duplicate paths invalid digests and missing indexed bytes fail loudly`() {
        val file = BmlAssetFile("a.js", ContentDigest.sha256("a"))
        assertFailsWith<IllegalArgumentException> { BmlApplicationAssets.validateFiles(listOf(file, file)) }
        assertFailsWith<IllegalArgumentException> { BmlApplicationAssets.validateFiles(listOf(file.copy(digest = "bad"))) }
        val manifest = Json.encodeToString(BmlClientManifest.serializer(), BmlClientManifest(files = listOf(file)))
        val reader = BmlApplicationAssets("app", emptyMap(), emptyMap()) { path ->
            if (path.endsWith("client-manifest.json")) manifest.encodeToByteArray() else null
        }
        assertFailsWith<IllegalArgumentException> { reader.read("a.js") }
    }
}
