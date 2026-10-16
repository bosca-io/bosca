package bosca.backup

import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.security.MessageDigest
import java.util.Objects
import kotlin.coroutines.CoroutineContext

/** Checks payload bytes as they are consumed, including a final length and digest check at EOF. */
internal class BackupPayloadInputStream(
    private val input: InputStream,
    private val entry: BackupObject,
    private val context: CoroutineContext,
) : InputStream() {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var length = 0L
    private var verified = false

    override fun read(): Int {
        context.ensureActive()
        if (verified) return -1

        val value = input.read()
        if (value == -1) {
            verifyEnd()
        } else {
            count(1)
            digest.update(value.toByte())
        }
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        Objects.checkFromIndexSize(offset, length, buffer.size)
        context.ensureActive()
        if (length == 0) return 0
        if (verified) return -1

        val read = input.read(buffer, offset, minOf(length, DEFAULT_BUFFER_SIZE))
        if (read == -1) {
            verifyEnd()
        } else {
            count(read)
            digest.update(buffer, offset, read)
        }
        return read
    }

    /** Rejects incomplete consumption and checks EOF when a writer stops at the declared length. */
    fun verifyComplete() {
        context.ensureActive()
        if (length != entry.length) {
            throw BackupPayloadMismatchException("Backup payload length mismatch: ${entry.payloadPath}")
        }
        if (!verified) read()
    }

    override fun close() {
        input.close()
    }

    private fun count(bytes: Int) {
        if (bytes.toLong() > entry.length - length) {
            throw BackupPayloadMismatchException("Backup payload exceeds expected length: ${entry.payloadPath}")
        }
        length += bytes
    }

    private fun verifyEnd() {
        if (length != entry.length) {
            throw BackupPayloadMismatchException("Backup payload length mismatch: ${entry.payloadPath}")
        }
        if (digest.digest().toHexString() != entry.sha256) {
            throw BackupPayloadMismatchException("Backup payload SHA-256 mismatch: ${entry.payloadPath}")
        }
        verified = true
    }
}
