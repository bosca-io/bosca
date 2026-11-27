package bosca.workops.ui

import kotlinx.serialization.Serializable

/** A closing move retains the inspected card version until the user confirms a resolution. */
@Serializable
data class WorkOpsBoardMove(val cardId: String, val columnId: String, val label: String, val transitionId: String, val version: Long)
