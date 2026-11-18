package bosca.ai.kit.harness

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** A source identity, never an executable, mount, credential or cached source body. */
@Serializable
data class KitContextSelection(
    val kind: KitContextSourceKind,
    @Contextual val id: UUID,
    val ref: String? = null,
    val path: String? = null,
) {
    /** Bounds persistent selection data and requires a repository-relative Git file path and ref. */
    fun validate() {
        if (kind == KitContextSourceKind.GIT_FILE) {
            require(!ref.isNullOrBlank() && ref.length <= 1024 && ref.none { it == '\u0000' || it == '\n' || it == '\r' }) { "Invalid Git context ref" }
            require(!path.isNullOrBlank() && path.length <= 1024 && !path.startsWith('/') &&
                path.split('/').none { it in setOf("", ".", "..") } && path.none { it == '\u0000' || it == '\n' || it == '\r' }) { "Invalid Git context path" }
        } else require(ref == null && path == null) { "Only Git file context has a ref or path" }
    }
}
