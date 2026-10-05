package bosca.artifacts.docker.routes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.bouncycastle.crypto.digests.SHA256Digest
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/** Resumable SHA-256 state used across requests in one Docker upload session. */
internal class DockerUploadDigest(encodedState: ByteArray?) {
    private val digest = encodedState?.let(::SHA256Digest) ?: SHA256Digest()

    /** Returns an output stream that updates this digest as bytes are forwarded. */
    fun outputStream(delegate: OutputStream): OutputStream = object : OutputStream() {
        override fun write(value: Int) {
            digest.update(value.toByte())
            delegate.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            digest.update(bytes, offset, length)
            delegate.write(bytes, offset, length)
        }

        override fun flush() = delegate.flush()

        override fun close() = delegate.close()
    }

    /** Encodes the current intermediate state for persistence between requests. */
    fun encodedState(): ByteArray = digest.encodedState

    /** Calculates the current digest without modifying the resumable state. */
    fun hexDigest(): String {
        val result = ByteArray(digest.digestSize)
        SHA256Digest(digest).doFinal(result, 0)
        return result.joinToString("") { "%02x".format(it) }
    }
}

/** Validates the inclusive OCI upload range against the session offset and request length. */
internal fun uploadRangeMatches(value: String?, expectedStart: Long, length: Long): Boolean {
    if (value == null || expectedStart < 0 || length <= 0) return false
    val match = UPLOAD_RANGE_PATTERN.matchEntire(value) ?: return false
    val start = match.groupValues[1].toLongOrNull() ?: return false
    val end = match.groupValues[2].toLongOrNull() ?: return false
    if (length - 1 > Long.MAX_VALUE - expectedStart) return false
    return start == expectedStart && end == expectedStart + length - 1
}

/**
 * Streams an HTTP request body into a blocking object-storage upload with bounded buffering.
 * The storage consumer runs on an I/O thread while Netty applies backpressure to the producer.
 */
internal suspend fun streamMultipartPart(
    expectedLength: Long?,
    digest: DockerUploadDigest,
    writeBody: suspend (OutputStream) -> Long,
    uploadPart: suspend (InputStream) -> Long,
): Long = coroutineScope {
    require(expectedLength == null || expectedLength > 0) { "multipart part length must be positive" }
    val input = PipedInputStream(PIPE_BUFFER_SIZE)
    val output = PipedOutputStream(input)
    val upload = async(Dispatchers.IO) {
        input.use { uploadPart(it) }
    }

    val written = digest.outputStream(output).use { writeBody(it) }
    require(expectedLength == null || written == expectedLength) {
        "request body length $written did not match Content-Length $expectedLength"
    }
    val uploaded = upload.await()
    check(uploaded == written) {
        "object storage wrote $uploaded bytes for a $written-byte request body"
    }
    written
}

private const val PIPE_BUFFER_SIZE = 1024 * 1024
private val UPLOAD_RANGE_PATTERN = Regex("^([0-9]+)-([0-9]+)$")
