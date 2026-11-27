package bosca.workops.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Trigger declarations and operator values stay fixed while an action dialog is open. */
@Serializable
data class WorkOpsReleaseCiLaunchDraft(
    val trigger: String,
    val environment: String = "",
    val inputs: WorkOpsReleaseCiInputs = WorkOpsReleaseCiInputs(),
    val values: JsonObject = inputs.defaults,
    val allowDowngrade: Boolean = false,
)
