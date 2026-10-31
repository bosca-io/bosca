package bosca.cli.swarm

import kotlinx.serialization.Serializable

/** Docker's local image identity, including full tag-and-digest references from containerd stores. */
@Serializable
internal data class SwarmStoredImage(
    val id: String,
    val tags: List<String>? = null,
    val digests: List<String>? = null,
) {
    val references: Set<String> get() = (tags.orEmpty() + digests.orEmpty()).toSet()
}
