package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** Deployment-owned connections to pinned Kubernetes profiles; private policy selects among these. */
@Serializable
data class KitHarnessDeployment(
    val profiles: List<KitHarnessConfiguration>,
    /** Maximum dispatched Kit generations across all profiles and principals, including cleanup. */
    val maxRuntimes: Int = 10,
    /** Maximum dispatched Kit generations owned by one initiating principal across all profiles. */
    val maxRuntimesPerPrincipal: Int = 2,
    /** Committed and pending archive bytes per principal, including objects awaiting deletion. */
    val maxArchiveBytesPerPrincipal: Long = 64L * 1024 * 1024 * 1024,
)
