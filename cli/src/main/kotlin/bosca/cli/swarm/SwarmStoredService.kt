package bosca.cli.swarm

import kotlinx.serialization.Serializable

/** Current and previous image specifications, read from the Swarm manager. */
@Serializable
internal data class SwarmStoredService(
    val name: String,
    val stack: String? = null,
    val image: String,
    val previous: String? = null,
)
