package bosca.workops.ui

import kotlinx.serialization.Serializable

@Serializable
data class WorkOpsSlaDraft(
    val name: String = "", val description: String = "", val timeZone: String = "America/New_York",
    val weeklyHours: String = """{
  "MONDAY": [{"startLocal":"09:00","endLocal":"17:00"}],
  "TUESDAY": [{"startLocal":"09:00","endLocal":"17:00"}],
  "WEDNESDAY": [{"startLocal":"09:00","endLocal":"17:00"}],
  "THURSDAY": [{"startLocal":"09:00","endLocal":"17:00"}],
  "FRIDAY": [{"startLocal":"09:00","endLocal":"17:00"}]
}""",
    val holidays: String = "[]",
)
