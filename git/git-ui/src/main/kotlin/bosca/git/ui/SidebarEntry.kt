package bosca.git.ui

import kotlinx.serialization.Serializable

@Serializable
data class SidebarEntry(
    val path: String,
    val name: String,
    val directory: Boolean,
    val depth: Int,
    val href: String,
    val expanded: Boolean = false,
) {
    val indent: String
        get() = "depth-${depth.coerceAtMost(12)}"
}
