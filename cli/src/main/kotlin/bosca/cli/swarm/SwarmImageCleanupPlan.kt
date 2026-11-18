package bosca.cli.swarm

/** Explicit local objects eligible for deletion; running containers and protected images are excluded. */
internal data class SwarmImageCleanupPlan(
    val containers: List<SwarmStoredContainer>,
    val images: List<SwarmStoredImage>,
)
