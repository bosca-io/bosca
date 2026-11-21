package bosca.workops.ui

import kotlinx.serialization.Serializable

@Serializable
data class WorkOpsSlaGoalRecord(val id: String, val name: String, val minutes: Int, val risk: Int, val start: String, val stop: String, val pause: String, val order: Int) {
    val duration get() = if (minutes < 60) "${minutes}m" else "${minutes / 60}h" + if (minutes % 60 != 0) " ${minutes % 60}m" else ""
}
