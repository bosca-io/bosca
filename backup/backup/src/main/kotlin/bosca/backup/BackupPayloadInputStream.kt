package bosca.backup

import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.security.MessageDigest
import java.util.Objects
import kotlin.coroutines.CoroutineContext

/** Measures payload bytes as they are consumed and verifies the expected length/digest at EOF. */
internal class BackupPayloadInputStream private constructor(
    private val input: InputStream,
    private val expectedLength: Long,
    private val payloadPath: String,
    private val expectedSha256: String?,
    private val context: CoroutineContext,
) : InputStream() {
    constructor(input: InputStream, entry: BackupObject, context: CoroutineContext) :
        this(input, entry.length, entry.payloadPath, entry.sha256, context)

    constructor(input: InputStream, length: Long, payloadPath: String, context: CoroutineContext) :
        this(input, length, payloadPath, null, context)

    private val digest = MessageDigest.getInstance("SHA-256")
    private var length = 0L
    private var verified = false
    private var sha256: String? = null

    init {
        require(expectedLength >= 0) { "Expected payload length must not be negative" }
    }

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

    /** Checks complete consumption/EOF and returns the derived digest of all source bytes. */
    fun verifyComplete(): String {
        context.ensureActive()
        if (length != expectedLength) {
            throw BackupPayloadMismatchException("Backup payload length mismatch: $payloadPath")
        }
        if (!verified) read()
        return checkNotNull(sha256) { "Backup payload digest is unavailable: $payloadPath" }
    }

    override fun close() {
        input.close()
    }

    private fun count(bytes: Int) {
        if (bytes.toLong() > expectedLength - length) {
            throw BackupPayloadMismatchException("Backup payload exceeds expected length: $payloadPath")
        }
        length += bytes
    }

    private fun verifyEnd() {
        if (length != expectedLength) {
            throw BackupPayloadMismatchException("Backup payload length mismatch: $payloadPath")
        }
        val actual = digest.digest().toHexString()
        if (expectedSha256 != null && actual != expectedSha256) {
            throw BackupPayloadMismatchException("Backup payload SHA-256 mismatch: $payloadPath")
        }
        sha256 = actual
        verified = true
    }
}
