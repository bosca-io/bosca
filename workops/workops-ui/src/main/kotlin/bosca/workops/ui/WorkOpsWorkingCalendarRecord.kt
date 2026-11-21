package bosca.workops.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class WorkOpsWorkingCalendarRecord(val id: String, val name: String, val description: String, val timeZone: String, val weeklyHours: JsonElement, val holidays: JsonElement)
