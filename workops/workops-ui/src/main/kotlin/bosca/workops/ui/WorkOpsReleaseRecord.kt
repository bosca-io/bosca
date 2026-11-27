package bosca.workops.ui

import bosca.workops.ui.graphql.IWorkOpsReleaseFields
import kotlinx.serialization.Serializable

/** Release facts and the separately loaded latest native Git CI status. */
@Serializable
data class WorkOpsReleaseRecord(
    val id: String,
    val programId: String,
    val name: String,
    val description: String = "",
    val planned: String = "",
    val released: String = "",
    val version: Long = 0,
    val latestRunId: String = "",
    val latestStatus: String = "",
    val statusLoaded: Boolean = false,
    val statusError: String = "",
) {
    val href get() = "/workops/releases/${workOpsEncode(id)}"
    val statusLabel get() = when {
        released.isNotBlank() -> "Released"
        !statusLoaded -> "Status unavailable"
        else -> when (latestStatus) {
            "" -> "Draft"
            "QUEUED" -> "Queued"
            "RUNNING" -> "Deploying"
            "SUCCESS" -> "Deployed"
            "FAILURE" -> "Failed"
            "CANCELLED" -> "Cancelled"
            "SKIPPED" -> "Skipped"
            else -> "Unknown status"
        }
    }
    val statusTone get() = when {
        released.isNotBlank() -> "success"
        !statusLoaded -> "neutral"
        latestStatus == "SUCCESS" -> "success"
        latestStatus == "FAILURE" -> "error"
        latestStatus == "RUNNING" -> "info"
        else -> "neutral"
    }
}

internal fun IWorkOpsReleaseFields.releaseRecord() = WorkOpsReleaseRecord(id, programId, name,
    description.orEmpty(), releaseDate.orEmpty(), releasedAt.orEmpty(), version)
