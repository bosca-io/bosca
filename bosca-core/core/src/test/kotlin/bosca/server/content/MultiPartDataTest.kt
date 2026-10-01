package bosca.server.content

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.netty.handler.codec.http.multipart.FileUpload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultiPartDataTest {

    // --- PartData.FormItem tests ---

    @Test
    fun `FormItem holds name and value`() {
        val item = PartData.FormItem("field", "value")
        assertEquals("field", item.name)
        assertEquals("value", item.value)
    }

    @Test
    fun `FormItem dispose is no-op`() {
        val item = PartData.FormItem("field", "value")
        item.dispose() // Should not throw
    }

    @Test
    fun `FormItem with null name`() {
        val item = PartData.FormItem(null, "value")
        assertNull(item.name)
    }

    // --- PartData.FileUploadItem tests ---

    private fun mockFileUpload(
        name: String? = "upload",
        filename: String? = "test.txt",
        contentType: String? = "text/plain",
        data: ByteArray = byteArrayOf(1, 2, 3),
        inMemory: Boolean = true
    ): FileUpload {
        val upload = mockk<FileUpload>(relaxed = true)
        every { upload.name } returns (name ?: "")
        every { upload.filename } returns (filename ?: "")
        every { upload.contentType } returns (contentType ?: "")
        every { upload.isInMemory } returns inMemory
        every { upload.get() } returns data
        return upload
    }

    @Test
    fun `FileUploadItem holds all properties`() {
        val upload = mockFileUpload()
        val item = PartData.FileUploadItem(upload)
        assertEquals("upload", item.name)
        assertEquals("test.txt", item.originalFileName)
        assertEquals("text/plain", item.contentType)
        val bytes = item.streamProvider.readBytes()
        assertEquals(3, bytes.size)
    }

    @Test
    fun `FileUploadItem dispose releases data`() {
        val upload = mockFileUpload()
        every { upload.refCnt() } returns 1
        val item = PartData.FileUploadItem(upload)
        item.dispose()
        verify { upload.release() }
    }

    // --- MultiPartData tests ---

    @Test
    fun `MultiPartData iterates over parts`() {
        val parts = listOf(
            PartData.FormItem("a", "1"),
            PartData.FormItem("b", "2")
        )
        val data = MultiPartData(parts)

        val collected = data.toList()
        assertEquals(2, collected.size)
        assertEquals("a", collected[0].name)
        assertEquals("b", collected[1].name)
    }

    @Test
    fun `readAllParts returns all parts`() {
        val upload = mockFileUpload()
        val parts = listOf(
            PartData.FormItem("x", "y"),
            PartData.FileUploadItem(upload)
        )
        val data = MultiPartData(parts)
        assertEquals(2, data.readAllParts().size)
    }

    @Test
    fun `forEachPart visits each part`() {
        val parts = listOf(
            PartData.FormItem("a", "1"),
            PartData.FormItem("b", "2"),
            PartData.FormItem("c", "3")
        )
        val data = MultiPartData(parts)

        val visited = mutableListOf<String>()
        data.forEachPart { part ->
            visited.add(part.name ?: "")
        }
        assertEquals(listOf("a", "b", "c"), visited)
    }

    @Test
    fun `empty MultiPartData`() {
        val data = MultiPartData(emptyList())
        assertEquals(0, data.readAllParts().size)
        assertTrue(data.toList().isEmpty())
    }

    @Test
    fun `parts property provides direct access`() {
        val parts = listOf(PartData.FormItem("f", "v"))
        val data = MultiPartData(parts)
        assertEquals(1, data.parts.size)
        assertTrue(data.parts[0] is PartData.FormItem)
    }
}
