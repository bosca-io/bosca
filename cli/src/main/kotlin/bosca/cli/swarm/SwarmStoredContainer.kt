package bosca.cli.swarm

import kotlinx.serialization.Serializable

/** Only the container metadata needed to retire old Swarm tasks, without environment secrets. */
@Serializable
internal data class SwarmStoredContainer(
    val id: String,
    val image: String,
    val reference: String,
    val service: String? = null,
    val state: String,
)
