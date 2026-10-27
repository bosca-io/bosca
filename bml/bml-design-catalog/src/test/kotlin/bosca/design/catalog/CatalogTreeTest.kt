package bosca.design.catalog

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CatalogTreeTest {
    @Test
    fun `rejected loading keeps the directory closed and retry does not affect another instance`(): Unit = runBlocking {
        val files = CatalogTree("first")
        val other = CatalogTree("other")
        files.toggleRejection()
        files.toggleDirectory()
        assertEquals(false, files.expanded)
        assertEquals("error", files.noticeTone)
        assertEquals(1, files.noticeSequence)
        files.toggleRejection()
        files.toggleDirectory()
        assertEquals(true, files.expanded)
        assertEquals(false, other.expanded)
        assertEquals(0, other.noticeSequence)
        files.toggleRejection()
        files.toggleDirectory()
        assertEquals(false, files.expanded)
        assertEquals(1, files.noticeSequence)
        files.toggleVisible()
        assertFailsWith<IllegalStateException> { files.toggleDirectory() }
    }

    @Test
    fun `local revisions remain separate from server disclosure across session serialization`(): Unit = runBlocking {
        val files = CatalogTree("files")
        files.toggleDirectory()
        files.replace()
        files.refresh()
        val restored = Json.decodeFromString(CatalogTree.serializer(), Json.encodeToString(CatalogTree.serializer(), files))
        assertEquals(true, restored.expanded)
        assertEquals(true, restored.alternate)
        assertEquals(1, restored.revision)
        assertEquals("info", restored.noticeTone)
        assertEquals(1, restored.noticeSequence)
        restored.replace()
        assertEquals(false, restored.alternate)
        assertEquals(2, restored.revision)
        assertEquals(true, restored.expanded)
    }
}
