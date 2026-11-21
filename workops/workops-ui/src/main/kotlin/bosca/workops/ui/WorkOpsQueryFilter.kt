package bosca.workops.ui

import kotlinx.serialization.Serializable

/** A caller-owned BQL filter linked into the selected WorkOps search surface. */
@Serializable
data class WorkOpsQueryFilter(val id: String, val name: String, val source: String, val path: String = "/workops/tasks") {
    val href: String get() = "$path?bql=${workOpsEncode(source)}"
}
