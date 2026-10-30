package bosca.cli.swarm

import kotlinx.serialization.Serializable

/** Image pins for the latest healthy deployment and the preceding distinct deployment. */
@Serializable
internal data class SwarmImageHistory(
    val current: Set<String> = emptySet(),
    val previous: Set<String> = emptySet(),
)
