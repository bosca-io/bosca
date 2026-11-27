package bosca.workops.ui

import kotlinx.serialization.Serializable

/** A status count for the project's loaded recent-task result, rather than its full backlog. */
@Serializable
data class WorkOpsProjectStatusCount(val label: String, val count: Int, val total: Int) {
    val percent get() = if (total == 0) 0.0 else count * 100.0 / total
}
