package bosca.artifacts

import java.security.MessageDigest

/**
 * Computes the SHA-256 hex digest of the given byte array.
 *
 * Used across artifact registry modules (Docker, Maven, npm) for
 * content-addressable blob identification.
 */
fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(bytes).joinToString("") { "%02x".format(it) }
}
