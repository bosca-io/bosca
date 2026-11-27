package bosca.workops.ui

import kotlinx.serialization.Serializable

/** An application or service identifier configured for the project's release telemetry. */
@Serializable
data class WorkOpsProjectIdentifier(val id: String, val value: String)
