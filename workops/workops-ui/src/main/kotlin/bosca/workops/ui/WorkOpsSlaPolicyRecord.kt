package bosca.workops.ui

import kotlinx.serialization.Serializable

@Serializable
data class WorkOpsSlaPolicyRecord(val id: String, val name: String, val description: String, val goals: List<WorkOpsSlaGoalRecord>) {
    val orderedGoals get() = goals.sortedWith(compareBy<WorkOpsSlaGoalRecord> { it.order }.thenBy { it.name })
}
