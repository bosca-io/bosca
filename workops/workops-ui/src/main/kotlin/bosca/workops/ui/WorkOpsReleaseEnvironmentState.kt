package bosca.workops.ui

import bosca.workops.ui.graphql.WorkOpsReleaseEnvironmentFactsData.WorkOps.MultiRepo.EnvironmentState as Deployment
import bosca.workops.ui.graphql.WorkOpsReleaseEnvironmentFactsData.WorkOps.MultiRepo.EnvironmentDrift as Drift
import kotlinx.serialization.Serializable

/** Retained environment data is explicitly stale until all three owning reads succeed together. */
@Serializable
data class WorkOpsReleaseEnvironmentState(
    val deployments: List<Deployment> = emptyList(),
    val drift: List<Drift> = emptyList(),
    val compatibleProjects: List<String> = emptyList(),
    val loaded: Boolean = false,
    val error: String = "",
) {
    val targets get() = deployments.filter { it.projectId in compatibleProjects }
    val differences get() = drift.filter { it.projectId in compatibleProjects }
    val deployedCount get() = targets.count { it.status.name == "DEPLOYED" }
    val status get() = when {
        !loaded -> "Unavailable"
        compatibleProjects.isEmpty() -> "No compatible projects"
        differences.isNotEmpty() -> "Differs"
        targets.isEmpty() -> "No environment targets"
        targets.any { it.status.name == "FAILED" } -> "Failed"
        targets.any { it.status.name == "ROLLED_BACK" } -> "Rolled back"
        deployedCount != targets.size -> "Deploying"
        compatibleProjects.any { project -> targets.none { it.projectId == project } } -> "Missing environment targets"
        else -> "In sync"
    }
    val tone get() = when (status) { "In sync" -> "success"; "Failed" -> "error"; "Differs", "Rolled back", "Missing environment targets" -> "warning"; "Deploying" -> "info"; else -> "neutral" }
}
