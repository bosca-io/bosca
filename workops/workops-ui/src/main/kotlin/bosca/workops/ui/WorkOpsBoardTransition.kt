package bosca.workops.ui

import kotlinx.serialization.Serializable

/** An available task transition resolves to its actual target status, independently of column order. */
@Serializable
data class WorkOpsBoardTransition(val id: String, val name: String, val statusId: String, val category: String) {
    val closing get() = category in setOf("DONE", "CANCELLED")
}
