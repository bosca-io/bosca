package bosca.artifacts.docker.routes

import kotlinx.coroutines.test.runTest
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DockerMultipartUploadTest {

    @Test
    fun `upload ranges must start at the persisted offset and cover the request body`() {
        assertEquals(true, uploadRangeMatches("0-4", 0, 5))
        assertEquals(true, uploadRangeMatches("5-11", 5, 7))
        assertEquals(false, uploadRangeMatches("4-10", 5, 7))
        assertEquals(false, uploadRangeMatches("5-10", 5, 7))
        assertEquals(false, uploadRangeMatches("bytes 5-11", 5, 7))
        assertEquals(false, uploadRangeMatches(null, 5, 7))
    }

    @Test
    fun `digest state resumes across requests`() {
        val first = "first chunk".encodeToByteArray()
        val second = " and second chunk".encodeToByteArray()
        val expected = MessageDigest.getInstance("SHA-256").digest(first + second).toHex()

        val initialDigest = DockerUploadDigest(null)
        initialDigest.outputStream(OutputStream.nullOutputStream()).use { it.write(first) }
        val resumedDigest = DockerUploadDigest(initialDigest.encodedState())
        resumedDigest.outputStream(OutputStream.nullOutputStream()).use { it.write(second) }

        assertEquals(expected, resumedDigest.hexDigest())
        assertEquals(MessageDigest.getInstance("SHA-256").digest(first).toHex(), initialDigest.hexDigest())
    }

    @Test
    fun `streamMultipartPart forwards the request once and updates its digest`() = runTest {
        val data = ByteArray(2 * 1024 * 1024 + 17) { (it % 251).toByte() }
        val digest = DockerUploadDigest(null)
        var stored = ByteArray(0)

        val written = streamMultipartPart(
            expectedLength = data.size.toLong(),
            digest = digest,
            writeBody = { output -> data.inputStream().copyTo(output) },
            uploadPart = { input ->
                stored = input.readBytes()
                stored.size.toLong()
            },
        )

        assertEquals(data.size.toLong(), written)
        assertContentEquals(data, stored)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(data).toHex(), digest.hexDigest())
    }

    @Test
    fun `streamMultipartPart accepts a request whose length is not declared`() = runTest {
        val data = "streamed without a content length".encodeToByteArray()
        val digest = DockerUploadDigest(null)
        var stored = ByteArray(0)

        val written = streamMultipartPart(
            expectedLength = null,
            digest = digest,
            writeBody = { output -> data.inputStream().copyTo(output) },
            uploadPart = { input ->
                stored = input.readBytes()
                stored.size.toLong()
            },
        )

        assertEquals(data.size.toLong(), written)
        assertContentEquals(data, stored)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(data).toHex(), digest.hexDigest())
    }

    @Test
    fun `streamMultipartPart rejects a request shorter than Content-Length`() = runTest {
        val data = "short".encodeToByteArray()

        assertFailsWith<IllegalArgumentException> {
            streamMultipartPart(
                expectedLength = data.size + 1L,
                digest = DockerUploadDigest(null),
                writeBody = { output -> data.inputStream().copyTo(output) },
                uploadPart = { input -> input.readBytes().size.toLong() },
            )
        }
    }

    @Test
    fun `streamMultipartPart rejects an object storage byte count mismatch`() = runTest {
        val data = "content".encodeToByteArray()

        assertFailsWith<IllegalStateException> {
            streamMultipartPart(
                expectedLength = data.size.toLong(),
                digest = DockerUploadDigest(null),
                writeBody = { output -> data.inputStream().copyTo(output) },
                uploadPart = { input ->
                    input.readBytes()
                    data.size - 1L
                },
            )
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
