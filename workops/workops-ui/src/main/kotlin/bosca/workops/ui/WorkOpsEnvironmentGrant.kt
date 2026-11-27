package bosca.workops.ui

import kotlinx.serialization.Serializable

/** An explicit environment grant, separate from inherited program access. */
@Serializable
data class WorkOpsEnvironmentGrant(val groupId: String, val action: String)
