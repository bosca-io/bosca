package bosca.backup

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

/** Byte identity of the content-reference JSON array captured from this database artifact's view. */
@Serializable
data class PostgresBackupContentInventory(
    val count: Long,
    val length: Long,
    val sha256: String,
    @Required
    val path: String = "content-references.json",
    @Required
    val version: Int = 1,
) {
    /** Checks the supported encoding, fixed relative path, record count and byte identity. */
    fun validate() {
        require(version == 1) { "Unsupported content-reference inventory version" }
        require(path == "content-references.json") { "Unsupported content-reference inventory path: $path" }
        require(count >= 0 && length >= 2) { "Invalid content-reference inventory count or length" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Content-reference inventory SHA-256 is required" }
    }
}
