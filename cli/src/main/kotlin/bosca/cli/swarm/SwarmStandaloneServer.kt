package bosca.cli.swarm

import kotlinx.serialization.Serializable

/** Explicit opt-in to a standalone service, independent of Atlas's enabled capabilities. */
@Serializable
data class SwarmStandaloneServer(val enabled: Boolean = false)
