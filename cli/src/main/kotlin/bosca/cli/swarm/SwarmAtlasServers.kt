package bosca.cli.swarm

import kotlinx.serialization.Serializable

/** Capabilities enabled inside the Atlas process; these flags never create standalone services. */
@Serializable
data class SwarmAtlasServers(
    val api: Boolean = true,
    val ui: Boolean = true,
    val git: Boolean = true,
    val artifacts: Boolean = true,
    val runner: Boolean = true,
)
