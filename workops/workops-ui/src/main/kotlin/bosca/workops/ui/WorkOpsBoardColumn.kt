package bosca.workops.ui

import kotlinx.serialization.Serializable

/** WorkOps owns status membership and WIP guidance for each shared board column. */
@Serializable
data class WorkOpsBoardColumn(val id: String, val name: String, val order: Int, val statusIds: List<String>, val wipLimit: Int?)
