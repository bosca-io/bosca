package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** Authorized source identity and content revision; contains no document body or title. */
@Serializable
data class KitContextSource(
    val kind: String, val id: String, val version: String, val revision: String, val contentAllowed: Boolean,
    val ref: String? = null,
    val path: String? = null,
    /** Linked content actually included in a WorkOps source; rechecked before restoring derived files. */
    val metadataId: String? = null,
)
