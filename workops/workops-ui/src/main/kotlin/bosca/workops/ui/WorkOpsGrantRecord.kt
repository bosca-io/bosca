package bosca.workops.ui

import kotlinx.serialization.Serializable

@Serializable
data class WorkOpsGrantRecord(val groupId: String, val groupName: String, val action: String) {
    val key get() = "$groupId-$action"
}
