package bosca.workops.ui

import kotlinx.serialization.Serializable

/** The identity inspected before a native CI or rollback confirmation. */
@Serializable
data class WorkOpsReleaseCiAction(
    val kind: String,
    val runId: String = "",
    val jobId: String = "",
    val environment: String = "",
    val label: String = "",
)
