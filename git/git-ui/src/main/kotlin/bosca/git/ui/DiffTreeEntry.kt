package bosca.git.ui

import kotlinx.serialization.Serializable

@Serializable
data class DiffTreeEntry(
    val path: String,
    val name: String,
    val directory: Boolean,
    val depth: Int,
) {
    val indent: String
        get() = "depth-${depth.coerceAtMost(12)}"

    val href: String
        get() = "#" + encode("diff-$path")
}
