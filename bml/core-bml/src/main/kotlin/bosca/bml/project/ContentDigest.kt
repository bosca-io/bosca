package bosca.bml.project

import java.security.MessageDigest
import java.util.HexFormat

/** Content-addressing for compiled artifacts/assets. */
object ContentDigest {
    private val hex = HexFormat.of()

    /** SHA-256 of [content] as `sha256:<hex>`. */
    fun sha256(content: String): String = sha256(content.toByteArray(Charsets.UTF_8))

    fun sha256(content: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(content)
        return "sha256:" + hex.formatHex(hash)
    }
}
