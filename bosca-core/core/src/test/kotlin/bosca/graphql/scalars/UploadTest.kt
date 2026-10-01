package bosca.graphql.scalars

import bosca.graphql.server.CoercingException
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UploadTest {

    @Test
    fun `parseValue returns UploadedFile`() {
        val uploadedFile = UploadedFile("test.txt", "text/plain", "test".byteInputStream())
        assertEquals(uploadedFile, Upload.Type.parseValue(uploadedFile))
    }

    @Test
    fun `parseValue extracts a completed UploadedFile future`() {
        val uploadedFile = UploadedFile("test.txt", "text/plain", "test".byteInputStream())
        assertEquals(uploadedFile, Upload.Type.parseValue(CompletableFuture.completedFuture(uploadedFile as Any)))
    }

    @Test
    fun `parseValue rejects incomplete or invalid values`() {
        assertFailsWith<CoercingException> { Upload.Type.parseValue(CompletableFuture<Any>()) }
        assertFailsWith<CoercingException> { Upload.Type.parseValue(CompletableFuture.completedFuture("not a file" as Any)) }
        assertFailsWith<CoercingException> { Upload.Type.parseValue("a string") }
    }
}
