package bosca.workops.ui

import kotlinx.serialization.Serializable

/** Form values remain strings until validation so rejected input is not lost. */
@Serializable
data class WorkOpsEnvironmentTypeDraft(var name: String = "", var description: String = "", var order: String = "0")
