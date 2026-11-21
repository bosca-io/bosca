package bosca.workops.ui

import kotlinx.serialization.Serializable

/** Snapshot membership includes tasks committed at start and tasks added during an active sprint. */
@Serializable
data class WorkOpsBoardSprint(val id: String, val name: String, val state: String, val endDate: String?, val taskIds: List<String>)
