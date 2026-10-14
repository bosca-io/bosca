package bosca.experimentation.model

import kotlinx.serialization.Serializable

/** Captured aggregation diagnostics; exclusions describe the configured identity sets. */
@Serializable
data class GoalDiagnostics(
    val excludedPrincipals: Long = 0, val excludedInstallations: Long = 0,
    val duplicateDeliveries: Long = 0, val outsideApplication: Long = 0,
    val missingApplication: Long = 0,
)
